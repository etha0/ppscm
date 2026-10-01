package kr.bujobank.scm;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.math.BigDecimal;
import java.nio.file.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import javax.imageio.ImageIO;

public class OrderPdfTest {
    private Map<String,Object> order(){Map<String,Object> o=new HashMap<>();o.put("id",42L);o.put("store_id",1L);o.put("created_at","2026-09-13 14:00");o.put("status","접수");o.put("store_name","서울 중앙 매장");o.put("actor_name","홍길동");o.put("note","배송 전 연락 부탁드립니다.");return o;}
    private Map<String,Object> line(int n){Map<String,Object> l=new HashMap<>();l.put("line_no",n);l.put("product_name","친환경 주방 세제 "+n);l.put("unit","상자 (12개입)");l.put("unit_price",new BigDecimal("1234.50"));l.put("quantity",3);return l;}
    @Test public void generatesKoreanPdfWithSavedPrices()throws Exception{
        byte[] bytes=OrderPdf.render(order(),Arrays.asList(line(1),line(2)));
        try(PDDocument pdf=Loader.loadPDF(bytes)){
            assertEquals(1,pdf.getNumberOfPages());String text=new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("서울 중앙 매장"));assertTrue(text.contains("7,407"));assertTrue(text.contains("친환경 주방 세제"));
            for(org.apache.pdfbox.cos.COSName name:pdf.getPage(0).getResources().getFontNames())assertTrue(pdf.getPage(0).getResources().getFont(name).isEmbedded());
            Path directory=Paths.get("target/pdf-preview");Files.createDirectories(directory);Files.write(directory.resolve("order.pdf"),bytes);
            ImageIO.write(new PDFRenderer(pdf).renderImageWithDPI(0,110),"PNG",directory.resolve("order.png").toFile());
        }
    }
    @Test public void paginatesLongNamesAndNotesAndRepeatsHeaders()throws Exception{
        Map<String,Object> o=order();StringBuilder note=new StringBuilder();for(int i=0;i<100;i++)note.append("긴 요청사항입니다. ");o.put("note",note.toString()+"😀");
        List<Map<String,Object>> lines=new ArrayList<>();for(int i=1;i<=120;i++){Map<String,Object> l=line(i);l.put("product_name","긴 상품명 자동 줄바꿈 확인용 친환경 세제 대용량 리필 상품 "+i);lines.add(l);}
        try(PDDocument pdf=Loader.loadPDF(OrderPdf.render(o,lines))){
            assertTrue(pdf.getNumberOfPages()>3);String text=new PDFTextStripper().getText(pdf);assertTrue(text.contains("444,420"));assertTrue(text.contains("120"));
            PDFTextStripper last=new PDFTextStripper();last.setStartPage(pdf.getNumberOfPages());assertTrue(last.getText(pdf).contains("총 발주 금액"));
            Path directory=Paths.get("target/pdf-preview");Files.createDirectories(directory);
            ImageIO.write(new PDFRenderer(pdf).renderImageWithDPI(1,110),"PNG",directory.resolve("continued.png").toFile());
        }
    }
    @Test public void storeCannotDownloadAnotherStoresOrder(){
        Map<String,Object> actor=new HashMap<>();actor.put("role","STORE_VIEW");actor.put("store_id",2L);
        try{Access.requireStore(actor,order());fail("Other store allowed");}catch(Access.Denied expected){}
        actor.put("store_id",1L);Access.requireStore(actor,order());actor.put("role","ADMIN");actor.put("store_id",null);Access.requireStore(actor,order());
    }
}
