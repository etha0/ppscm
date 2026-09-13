package kr.bujobank.scm;

import java.sql.*;
import static kr.bujobank.scm.Database.*;

/** Explicit, restartable upgrade preserving existing product rows and category labels. */
public final class CategoryMigration {
    private CategoryMigration() {}
    public static void main(String[] args) throws Exception {
        try (Connection c = new Database().open()) { migrate(c); }
        System.out.println("Category migration completed.");
    }
    public static void migrate(Connection c) throws SQLException {
        String lock = "scm_categories_" + one(c,"SELECT DATABASE() db").get("db");
        if (number(one(c,"SELECT GET_LOCK(?,10) acquired",lock),"acquired") != 1) throw new SQLException("카테고리 마이그레이션이 이미 진행 중입니다.");
        try {
            execute(c,"CREATE TABLE IF NOT EXISTS categories (id BIGINT PRIMARY KEY AUTO_INCREMENT,name VARCHAR(60) NOT NULL UNIQUE,sort_order INT NOT NULL DEFAULT 0,active BOOLEAN NOT NULL DEFAULT TRUE,version INT NOT NULL DEFAULT 0,CHECK(sort_order>=0)) ENGINE=InnoDB");
            if(number(one(c,"SELECT COUNT(*) n FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='products' AND column_name='category_id'"),"n")==0)
                execute(c,"ALTER TABLE products ADD COLUMN category_id BIGINT NULL AFTER category");
            execute(c,"INSERT INTO categories(name) SELECT DISTINCT COALESCE(NULLIF(TRIM(p.category),''),'미분류') FROM products p WHERE p.category_id IS NULL AND NOT EXISTS (SELECT 1 FROM categories c WHERE c.name=COALESCE(NULLIF(TRIM(p.category),''),'미분류'))");
            execute(c,"UPDATE products p JOIN categories c ON c.name=COALESCE(NULLIF(TRIM(p.category),''),'미분류') SET p.category_id=c.id WHERE p.category_id IS NULL");
            if(number(one(c,"SELECT COUNT(*) n FROM products p LEFT JOIN categories c ON c.id=p.category_id WHERE c.id IS NULL"),"n")!=0)throw new SQLException("분류되지 않은 상품이 있어 마이그레이션을 완료할 수 없습니다.");
            if(number(one(c,"SELECT COUNT(*) n FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='products' AND column_name='category_id' AND is_nullable='YES'"),"n")>0)
                execute(c,"ALTER TABLE products MODIFY category_id BIGINT NOT NULL");
            if(number(one(c,"SELECT COUNT(*) n FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='products' AND constraint_name='fk_products_category'"),"n")==0)
                execute(c,"ALTER TABLE products ADD CONSTRAINT fk_products_category FOREIGN KEY(category_id) REFERENCES categories(id)");
        } finally { one(c,"SELECT RELEASE_LOCK(?) released",lock); }
    }
}