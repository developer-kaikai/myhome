package com.myhome.table.core.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhome.table.common.exception.ApiException;
import com.myhome.table.common.persistence.HomeStatisticsCache;
import com.myhome.table.common.persistence.IdempotencyService;
import com.myhome.table.common.security.UserContext;
import com.myhome.table.common.util.TextRules;
import com.myhome.table.core.domain.MenuModels.*;
import com.myhome.table.core.infrastructure.MenuRepository;
import com.myhome.table.core.infrastructure.MenuRepository.DishRow;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MenuService {
  private final MenuRepository repo;
  private final IdempotencyService idem;
  private final ObjectMapper json;
  private final Clock clock;
  private final HomeStatisticsCache homeCache;

  public MenuService(
      MenuRepository repo,
      IdempotencyService idem,
      ObjectMapper json,
      Clock clock,
      HomeStatisticsCache homeCache) {
    this.repo = repo;
    this.idem = idem;
    this.json = json;
    this.clock = clock;
    this.homeCache = homeCache;
  }

  public List<RestaurantView> restaurants(UserContext user) {
    user.requireDaily(clock.instant());
    return repo.restaurants();
  }

  @Transactional(readOnly = true)
  public Menu menu(UserContext user, long restaurant, LocalDate date, boolean chef) {
    if (chef) user.requireChef(restaurant);
    else user.requireDaily(clock.instant());
    var r = repo.restaurant(restaurant, false);
    var categories = repo.categories(restaurant);
    var seasonal = new HashSet<Long>();
    categories.stream().filter(c -> c.type().equals("SEASONAL")).forEach(c -> seasonal.add(c.id()));
    var dishes =
        repo.views(restaurant).stream()
            .filter(
                d ->
                    chef
                        || d.onShelf()
                            && (!seasonal.contains(d.categoryId())
                                || d.supplyMonths().contains(date.getMonthValue())))
            .toList();
    var eligible = dishes;
    if (!chef)
      categories =
          categories.stream()
              .filter(c -> eligible.stream().anyMatch(d -> d.categoryId() == c.id()))
              .toList();
    if (!chef) {
      var hot =
          repo.hot(
              restaurant,
              date,
              LocalDate.now(clock.withZone(com.myhome.table.common.util.BusinessTime.ZONE)));
      dishes =
          dishes.stream()
              .map(
                  d ->
                      new DishView(
                          d.id(),
                          d.categoryId(),
                          d.name(),
                          d.introduction(),
                          d.onShelf(),
                          d.version(),
                          d.supplyMonths(),
                          d.dimensions(),
                          d.average(),
                          d.reviewCount(),
                          hot.contains(d.id())))
              .toList();
    }
    return new Menu(r, date, categories, dishes);
  }

  @Transactional(readOnly = true)
  public DishView customerDish(UserContext user, long restaurant, long id, LocalDate date) {
    return menu(user, restaurant, date, false).dishes().stream()
        .filter(d -> d.id() == id)
        .findFirst()
        .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "该菜品当前不可选"));
  }

  @Transactional(readOnly = true)
  public ChefDish chefDish(UserContext user, long id) {
    var row = repo.dish(id, false);
    user.requireChef(row.restaurantId());
    return new ChefDish(view(row), row.recipe());
  }

  private DishView view(DishRow d) {
    var score =
        repo.scores(d.restaurantId()).getOrDefault(d.id(), new MenuRepository.Score(null, 0));
    return new DishView(
        d.id(),
        d.categoryId(),
        d.name(),
        d.introduction(),
        d.onShelf(),
        d.version(),
        repo.months(d.id()),
        repo.dimensions(d.id()),
        score.average(),
        score.count(),
        false);
  }

  @Transactional
  public RestaurantView renameRestaurant(UserContext user, long id, String name, Long version) {
    user.requireChef(id);
    var row = repo.restaurant(id, true);
    version(row.version(), version);
    repo.jdbc()
        .update(
            "UPDATE restaurant SET name=?,version=version+1 WHERE id=?", text(name, 20, false), id);
    homeCache.invalidateAfterCommit();
    return repo.restaurant(id, false);
  }

  public Created createCategory(UserContext user, long restaurant, String name, String key) {
    user.requireChef(restaurant);
    String clean = text(name, 10, false);
    return idem.execute(
        user.userId(),
        "menu/category/create/" + restaurant,
        key,
        encode(clean),
        Created.class,
        () -> {
          repo.restaurant(restaurant, true);
          if (repo.categories(restaurant).size() >= 30)
            throw ApiException.invalid("每店最多30个品类（含季节限定）");
          try {
            return new Created(
                repo.insert(
                    "INSERT INTO menu_category(restaurant_id,name) VALUES(?,?)",
                    restaurant,
                    clean));
          } catch (DuplicateKeyException e) {
            throw duplicate();
          }
        });
  }

  @Transactional
  public Category renameCategory(UserContext user, long id, String name, Long version) {
    var initial = repo.category(id);
    user.requireChef(initial.restaurantId());
    repo.restaurant(initial.restaurantId(), true);
    var row = repo.category(id);
    version(row.version(), version);
    normal(row);
    try {
      repo.jdbc()
          .update(
              "UPDATE menu_category SET name=?,version=version+1 WHERE id=?",
              text(name, 10, false),
              id);
    } catch (DuplicateKeyException e) {
      throw duplicate();
    }
    return repo.category(id);
  }

  @Transactional
  public void deleteCategory(UserContext user, long id, Long version) {
    var initial = repo.category(id);
    user.requireChef(initial.restaurantId());
    repo.restaurant(initial.restaurantId(), true);
    var row = repo.category(id);
    version(row.version(), version);
    normal(row);
    if (repo.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM dish WHERE category_id=? AND deleted_at IS NULL",
                Long.class,
                id)
        > 0) throw ApiException.invalid("品类中还有菜品，请先移动或删除（含已下架菜品）");
    repo.jdbc()
        .update(
            "UPDATE menu_category SET deleted_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",
            id);
  }

  public Created createDish(UserContext user, long restaurant, DishForm form, String key) {
    user.requireChef(restaurant);
    validate(form, false);
    return idem.execute(
        user.userId(),
        "menu/dish/create/" + restaurant,
        key,
        encode(form),
        Created.class,
        () -> {
          repo.restaurant(restaurant, true);
          categoryFor(restaurant, form);
          if (repo.jdbc()
                  .queryForObject(
                      "SELECT COUNT(*) FROM dish WHERE restaurant_id=? AND deleted_at IS NULL",
                      Long.class,
                      restaurant)
              >= 500) throw ApiException.invalid("每店最多500道未删除菜品");
          try {
            long id =
                repo.insert(
                    "INSERT INTO dish(restaurant_id,category_id,name,introduction,recipe,is_on_shelf) VALUES(?,?,?,?,?,?)",
                    restaurant,
                    form.categoryId(),
                    text(form.name(), 20, false),
                    text(form.introduction(), 50, true),
                    text(form.recipe(), 500, true),
                    form.onShelf());
            saveChildren(id, form);
            homeCache.invalidateAfterCommit();
            return new Created(id);
          } catch (DuplicateKeyException e) {
            throw duplicate();
          }
        });
  }

  @Transactional
  public ChefDish updateDish(UserContext user, long id, DishForm form) {
    var initial = repo.dish(id, false);
    user.requireChef(initial.restaurantId());
    validate(form, true);
    repo.restaurant(initial.restaurantId(), true);
    var row = repo.dish(id, true);
    version(row.version(), form.version());
    categoryFor(row.restaurantId(), form);
    validateIds(id, form.dimensions());
    try {
      repo.jdbc()
          .update(
              "UPDATE dish SET category_id=?,name=?,introduction=?,recipe=?,is_on_shelf=?,version=version+1 WHERE id=?",
              form.categoryId(),
              text(form.name(), 20, false),
              text(form.introduction(), 50, true),
              text(form.recipe(), 500, true),
              form.onShelf(),
              id);
      saveChildren(id, form);
    } catch (DuplicateKeyException e) {
      throw duplicate();
    }
    homeCache.invalidateAfterCommit();
    return chefDish(user, id);
  }

  @Transactional
  public void deleteDish(UserContext user, long id, Long version) {
    var initial = repo.dish(id, false);
    user.requireChef(initial.restaurantId());
    repo.restaurant(initial.restaurantId(), true);
    var row = repo.dish(id, true);
    version(row.version(), version);
    repo.jdbc()
        .update("UPDATE dish SET deleted_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?", id);
    homeCache.invalidateAfterCommit();
  }

  private void categoryFor(long restaurant, DishForm form) {
    var category = repo.category(form.categoryId());
    if (category.restaurantId() != restaurant) throw ApiException.invalid("只能选择本餐厅品类");
    if (category.type().equals("SEASONAL") && form.supplyMonths().isEmpty())
      throw ApiException.invalid("季节菜至少选择一个供应月份");
    if (!category.type().equals("SEASONAL") && !form.supplyMonths().isEmpty())
      throw ApiException.invalid("普通菜品不设置供应月份");
  }

  private void validateIds(long dish, List<Dimension> dimensions) {
    var existing = repo.dimensions(dish);
    for (var dim : dimensions) {
      if (dim.id() == null) {
        if (dim.options().stream().anyMatch(o -> o.id() != null))
          throw ApiException.invalid("新规格不能引用旧选项");
        continue;
      }
      var old =
          existing.stream()
              .filter(d -> d.id().equals(dim.id()))
              .findFirst()
              .orElseThrow(() -> ApiException.invalid("规格已失效，请刷新"));
      for (var option : dim.options())
        if (option.id() != null
            && old.options().stream().noneMatch(o -> o.id().equals(option.id())))
          throw ApiException.invalid("规格选项已失效，请刷新");
    }
  }

  private void saveChildren(long dish, DishForm form) {
    repo.jdbc().update("DELETE FROM dish_supply_month WHERE dish_id=?", dish);
    for (int month : form.supplyMonths())
      repo.jdbc()
          .update("INSERT INTO dish_supply_month(dish_id,supply_month) VALUES(?,?)", dish, month);
    // 先使旧名称/默认项退出唯一索引，再恢复保留的 ID；历史与待提交清单引用不会被重新编号。
    repo.jdbc()
        .update(
            "UPDATE dish_spec_option o JOIN dish_spec_dimension d ON d.id=o.dimension_id SET o.deleted_at=UTC_TIMESTAMP(3),o.version=o.version+1 WHERE d.dish_id=? AND o.deleted_at IS NULL",
            dish);
    repo.jdbc()
        .update(
            "UPDATE dish_spec_dimension SET deleted_at=UTC_TIMESTAMP(3),version=version+1 WHERE dish_id=? AND deleted_at IS NULL",
            dish);
    for (int i = 0; i < form.dimensions().size(); i++) {
      var dim = form.dimensions().get(i);
      long dimension =
          dim.id() == null
              ? repo.insert(
                  "INSERT INTO dish_spec_dimension(dish_id,name,sort_order) VALUES(?,?,?)",
                  dish,
                  text(dim.name(), 10, false),
                  i)
              : dim.id();
      if (dim.id() != null)
        repo.jdbc()
            .update(
                "UPDATE dish_spec_dimension SET name=?,sort_order=?,deleted_at=NULL WHERE id=?",
                text(dim.name(), 10, false),
                i,
                dimension);
      for (int j = 0; j < dim.options().size(); j++) {
        var option = dim.options().get(j);
        if (option.id() == null)
          repo.insert(
              "INSERT INTO dish_spec_option(dimension_id,name,is_default,sort_order) VALUES(?,?,?,?)",
              dimension,
              text(option.name(), 10, false),
              option.isDefault(),
              j);
        else
          repo.jdbc()
              .update(
                  "UPDATE dish_spec_option SET name=?,is_default=?,sort_order=?,deleted_at=NULL WHERE id=?",
                  text(option.name(), 10, false),
                  option.isDefault(),
                  j,
                  option.id());
      }
    }
  }

  private void validate(DishForm form, boolean update) {
    if (form == null
        || form.categoryId() == null
        || form.categoryId() <= 0
        || form.onShelf() == null
        || form.dimensions() == null
        || form.supplyMonths() == null) throw ApiException.invalid("请填写完整菜品信息");
    text(form.name(), 20, false);
    text(form.introduction(), 50, true);
    text(form.recipe(), 500, true);
    if (!update
        && (form.version() != null
            || form.dimensions().stream()
                .filter(Objects::nonNull)
                .anyMatch(
                    d ->
                        d.id() != null
                            || d.options() != null
                                && d.options().stream()
                                    .filter(Objects::nonNull)
                                    .anyMatch(o -> o.id() != null))))
      throw ApiException.invalid("新菜品不能引用旧规格");
    if (form.supplyMonths().size() > 12
        || new HashSet<>(form.supplyMonths()).size() != form.supplyMonths().size()
        || form.supplyMonths().stream().anyMatch(m -> m == null || m < 1 || m > 12))
      throw ApiException.invalid("供应月份须为不重复的1—12月");
    if (form.dimensions().size() > 3) throw ApiException.invalid("每菜最多3个规格维度");
    var names = new HashSet<String>();
    var ids = new HashSet<Long>();
    for (var dim : form.dimensions()) {
      if (dim == null
          || !names.add(text(dim.name(), 10, false))
          || dim.id() != null && (!ids.add(dim.id()) || dim.id() <= 0))
        throw ApiException.invalid("规格维度不能重复");
      if (dim.options() == null || dim.options().size() < 2 || dim.options().size() > 6)
        throw ApiException.invalid("每个规格需要2—6个选项");
      var options = new HashSet<String>();
      var optionIds = new HashSet<Long>();
      for (var option : dim.options())
        if (option == null
            || !options.add(text(option.name(), 10, false))
            || option.id() != null && (!optionIds.add(option.id()) || option.id() <= 0))
          throw ApiException.invalid("规格选项不能重复");
      if (dim.options().stream().filter(Option::isDefault).count() != 1)
        throw ApiException.invalid("每个规格须且仅须一个默认选项");
    }
  }

  private static String text(String value, int max, boolean optional) {
    if (value == null || value.isBlank()) {
      if (optional) return null;
      throw ApiException.invalid("名称不能为空");
    }
    return TextRules.required(value, max, max == 500 ? 8192 : max == 50 ? 1024 : 255, optional);
  }

  private static void normal(Category c) {
    if (c.type().equals("SEASONAL")) throw ApiException.invalid("季节限定品类不可更名或删除");
  }

  private static void version(long actual, Long requested) {
    if (requested == null || requested < 0) throw ApiException.invalid("请提供版本号");
    if (actual != requested) throw ApiException.conflict();
  }

  private static ApiException duplicate() {
    return new ApiException(409, "NAME_DUPLICATE", "该名称已存在，请使用其他名称");
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw ApiException.invalid("请求格式错误");
    }
  }
}
