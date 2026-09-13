package kr.bujobank.scm;
import java.sql.*;
import java.util.*;
import java.io.IOException;
import static kr.bujobank.scm.Database.*;

/** Moves legacy BLOBs to numbered JPEGs, without overwriting unrelated files. */
public final class ProductImageMigration {
    public static void migrate(Database db,ProductCodeImages storage)throws Exception{
        if(!storage.enabled())return;
        List<Map<String,Object>> products;
        try(Connection c=db.open()){products=rows(c,"SELECT DISTINCT product_id FROM product_images");}
        for(Map<String,Object> product:products){
            try(ProductCodeImages.Change files=storage.change()){
                db.transaction(c->{
                    long id=number(product,"product_id");
                    Map<String,Object> p=one(c,"SELECT code FROM products WHERE id=? FOR UPDATE",id);
                    List<Map<String,Object>> old=rows(c,"SELECT content FROM product_images WHERE product_id=? ORDER BY sort_no,id",id);
                    if(p==null||old.isEmpty())return null;
                    String code=(String)p.get("code");
                    List<byte[]> images=new ArrayList<>();
                    for(Map<String,Object> image:old){byte[] bytes=(byte[])image.get("content");if(bytes==null||bytes.length==0)throw new IOException("기존 파일 이미지의 수동 복원이 필요합니다: "+code);images.add(ImageFiles.jpeg(bytes));}
                    List<Integer> existing=storage.sequences(code);
                    if(!existing.isEmpty()){
                        if(existing.size()!=images.size())throw new IOException("기존 이미지 파일과 DB 이미지가 충돌합니다: "+code);
                        for(int i=0;i<images.size();i++)if(existing.get(i)!=i+1||!Arrays.equals(storage.read(code,i+1),images.get(i)))throw new IOException("기존 이미지 파일과 DB 이미지가 충돌합니다: "+code);
                    }else files.replace(null,code,images);
                    execute(c,"DELETE FROM product_images WHERE product_id=?",id);
                    return null;
                });
                files.commit();
            }
        }
    }
}
