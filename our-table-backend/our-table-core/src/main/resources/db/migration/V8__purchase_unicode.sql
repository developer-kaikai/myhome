-- 可见字数由应用按字素簇校验，扩展物理容量以容纳复合表情。
ALTER TABLE party_purchase_item
    MODIFY item_name VARCHAR(255) NOT NULL COMMENT '采购物品名称，业务最多20个可见字符',
    MODIFY quantity_text VARCHAR(255) NULL COMMENT '数量单位说明，业务最多10个可见字符',
    MODIFY zero_amount_note VARCHAR(1000) NULL COMMENT '零元采购说明，业务最多100个可见字符',
    MODIFY correction_reason VARCHAR(1000) NULL COMMENT '采购纠错原因，业务最多100个可见字符',
    MODIFY remove_reason VARCHAR(1000) NULL COMMENT '移除采购项原因，业务最多100个可见字符';
