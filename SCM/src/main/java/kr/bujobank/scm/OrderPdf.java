package kr.bujobank.scm;

import java.io.*;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/** A4 purchase order, generated from the order's saved prices and product names. */
public final class OrderPdf {
    private final PDDocument document;
    private final PDType0Font font;
    private PDPageContentStream stream;
    private float y;
    private final String orderNumber;
    private final DecimalFormat money=new DecimalFormat("#,##0.##");
    private static final float LEFT=40, WIDTH=515;
    private static final float[] COLS={32,169,82,44,90,98};
    private OrderPdf(PDDocument document,PDType0Font font,String orderNumber){this.document=document;this.font=font;this.orderNumber=orderNumber;}
    public static byte[] render(Map<String,Object> order,List<Map<String,Object>> lines)throws IOException{
        try(PDDocument document=new PDDocument();InputStream input=OrderPdf.class.getResourceAsStream("/fonts/NanumGothic-Regular.ttf")){
            if(input==null)throw new IOException("발주서 한글 폰트가 없습니다.");
            OrderPdf pdf=new OrderPdf(document,PDType0Font.load(document,input,true),String.format(Locale.ROOT,"PO-%08d",Database.number(order,"id")));
            document.getDocumentInformation().setTitle("발주서 "+pdf.orderNumber);
            document.getDocumentInformation().setAuthor("PING-PONG SCM");
            try{pdf.build(order,lines);}finally{if(pdf.stream!=null)pdf.stream.close();}
            pdf.stream=null;
            int count=document.getNumberOfPages();
            for(int i=0;i<count;i++)try(PDPageContentStream footer=new PDPageContentStream(document,document.getPage(i),PDPageContentStream.AppendMode.APPEND,true,true)){
                footer.beginText();footer.setFont(pdf.font,9);footer.newLineAtOffset(LEFT,28);footer.showText(pdf.orderNumber+"     |     "+(i+1)+" / "+count);footer.endText();
            }
            ByteArrayOutputStream output=new ByteArrayOutputStream();document.save(output);return output.toByteArray();
        }
    }
    private void page()throws IOException{
        if(stream!=null)stream.close();
        PDPage page=new PDPage(PDRectangle.A4);document.addPage(page);stream=new PDPageContentStream(document,page);
        y=797;text("발주서",LEFT,y,23);text("PING-PONG SCM",435,y,11);y-=25;text(orderNumber,LEFT,y,10);y-=25;
    }
    private String clean(Object value)throws IOException{
        String source=value==null?"":value.toString();StringBuilder result=new StringBuilder();
        for(int offset=0;offset<source.length();){int cp=source.codePointAt(offset);offset+=Character.charCount(cp);
            if(cp=='\n'){result.append('\n');continue;}if(Character.isISOControl(cp)){result.append(' ');continue;}
            String glyph=new String(Character.toChars(cp));try{font.encode(glyph);result.append(glyph);}catch(IllegalArgumentException e){result.append('?');}
        }return result.toString();
    }
    private List<String> wrap(Object value,float width,float size)throws IOException{
        List<String> rows=new ArrayList<>();
        for(String paragraph:clean(value).split("\n",-1)){
            StringBuilder row=new StringBuilder();
            for(int i=0;i<paragraph.length();){int cp=paragraph.codePointAt(i);i+=Character.charCount(cp);String next=new String(Character.toChars(cp));
                if(row.length()>0&&font.getStringWidth(row.toString()+next)*size/1000>width){rows.add(row.toString());row.setLength(0);}row.append(next);
            }rows.add(row.toString());
        }return rows;
    }
    private void text(String value,float x,float baseline,float size)throws IOException{
        stream.setNonStrokingColor(new java.awt.Color(35,48,68));stream.beginText();stream.setFont(font,size);stream.newLineAtOffset(x,baseline);stream.showText(value);stream.endText();
    }
    private void block(String value)throws IOException{
        for(String row:wrap(value,WIDTH,10)){if(y<65)page();text(row,LEFT,y,10);y-=16;}y-=7;
    }
    private void tableHeader()throws IOException{row(new String[]{"번호","상품명","포장 단위","수량","확정 단가","금액"},true);}
    private void row(String[] values,boolean header)throws IOException{
        List<List<String>> cells=new ArrayList<>();int length=1;
        for(int i=0;i<values.length;i++){List<String> parts=wrap(values[i],COLS[i]-12,9);cells.add(parts);length=Math.max(length,parts.size());}
        float height=length*14+14;
        if(y-height<58){page();if(!header)tableHeader();}
        if(header){stream.setNonStrokingColor(new java.awt.Color(232,238,248));stream.addRect(LEFT,y-height,WIDTH,height);stream.fill();}
        float x=LEFT;
        for(int i=0;i<cells.size();i++){
            for(int j=0;j<cells.get(i).size();j++){
                String value=cells.get(i).get(j);float tx=x+6;
                if(!header&&i>=3)tx=x+COLS[i]-6-font.getStringWidth(value)*9/1000;
                text(value,tx,y-17-j*14,9);
            }x+=COLS[i];
        }
        stream.setStrokingColor(new java.awt.Color(214,222,234));stream.setLineWidth(.5f);stream.moveTo(LEFT,y-height);stream.lineTo(LEFT+WIDTH,y-height);stream.stroke();y-=height;
    }
    private void build(Map<String,Object> order,List<Map<String,Object>> lines)throws IOException{
        page();block("발주일: "+Objects.toString(order.get("created_at"),"")+"     상태: "+Objects.toString(order.get("status"),""));
        block("발주 매장: "+Objects.toString(order.get("store_name"),""));
        block("담당자: "+Objects.toString(order.get("actor_name"),""));
        String note=Objects.toString(order.get("note"),"");block("요청사항: "+(note.isEmpty()?"없음":note));
        if("취소".equals(order.get("status")))block("취소 사유: "+Objects.toString(order.get("cancel_reason"),""));
        if(y<120)page();tableHeader();BigDecimal total=BigDecimal.ZERO;
        for(Map<String,Object> line:lines){
            BigDecimal price=new BigDecimal(line.get("unit_price").toString());long quantity=Database.number(line,"quantity");BigDecimal amount=price.multiply(BigDecimal.valueOf(quantity));total=total.add(amount);
            row(new String[]{Objects.toString(line.get("line_no"),""),Objects.toString(line.get("product_name"),""),Objects.toString(line.get("unit"),""),Long.toString(quantity),money.format(price),money.format(amount)},false);
        }
        if(lines.isEmpty())block("발주 품목이 없습니다.");
        y-=18;block("총 발주 금액: "+money.format(total)+"원");block("단가와 금액은 발주 접수 시점의 확정 가격입니다.");
    }
}
