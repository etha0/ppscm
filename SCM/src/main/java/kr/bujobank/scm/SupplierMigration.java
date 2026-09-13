package kr.bujobank.scm;

import java.sql.*;
import static kr.bujobank.scm.Database.*;

/** Restartable upgrade; historical supplier labels are never overwritten. */
public final class SupplierMigration {
    private SupplierMigration() {}
    public static void main(String[] args)throws Exception {
        try(Connection c=new Database().open()){migrate(c);}
        System.out.println("Supplier migration completed.");
    }
    public static void migrate(Connection c)throws SQLException {
        String lock="scm_suppliers_"+one(c,"SELECT DATABASE() db").get("db");
        if(number(one(c,"SELECT GET_LOCK(?,10) acquired",lock),"acquired")!=1)throw new SQLException("Supplier migration already running.");
        try {
            execute(c,"CREATE TABLE IF NOT EXISTS suppliers (id BIGINT PRIMARY KEY AUTO_INCREMENT,name VARCHAR(100) NOT NULL UNIQUE,contact VARCHAR(100) NOT NULL DEFAULT '',phone VARCHAR(40) NOT NULL DEFAULT '',address VARCHAR(300) NOT NULL DEFAULT '',active BOOLEAN NOT NULL DEFAULT TRUE,version INT NOT NULL DEFAULT 0) ENGINE=InnoDB");
            if(number(one(c,"SELECT COUNT(*) n FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='inventory_events' AND column_name='supplier_id'"),"n")==0)
                execute(c,"ALTER TABLE inventory_events ADD COLUMN supplier_id BIGINT NULL AFTER supplier");
            execute(c,"INSERT INTO suppliers(name) SELECT DISTINCT TRIM(e.supplier) FROM inventory_events e WHERE e.kind='입고' AND e.supplier_id IS NULL AND TRIM(e.supplier)<>'' AND NOT EXISTS (SELECT 1 FROM suppliers s WHERE s.name=TRIM(e.supplier))");
            execute(c,"UPDATE inventory_events e JOIN suppliers s ON s.name=TRIM(e.supplier) SET e.supplier_id=s.id WHERE e.kind='입고' AND e.supplier_id IS NULL AND TRIM(e.supplier)<>''");
            if(number(one(c,"SELECT COUNT(*) n FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='inventory_events' AND constraint_name='fk_inventory_supplier'"),"n")==0)
                execute(c,"ALTER TABLE inventory_events ADD CONSTRAINT fk_inventory_supplier FOREIGN KEY(supplier_id) REFERENCES suppliers(id)");
        }finally{one(c,"SELECT RELEASE_LOCK(?) released",lock);}
    }
}
