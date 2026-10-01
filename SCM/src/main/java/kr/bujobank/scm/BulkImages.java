package kr.bujobank.scm;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.servlet.http.Part;
public final class BulkImages {
    public static final class Item{public final String code;public final int sequence;public final byte[] bytes;Item(String code,int sequence,byte[] bytes){this.code=code;this.sequence=sequence;this.bytes=bytes;}}
    private final List<Item> items=new ArrayList<>();private final Set<String> names=new HashSet<>();private long total,converted;private int entries;
    public static List<Item> read(Collection<Part> parts)throws IOException{
        BulkImages batch=new BulkImages();
        for(Part part:parts){if(!"batch_images".equals(part.getName())||part.getSize()==0)continue;
            String name=part.getSubmittedFileName();if(name==null)throw new IllegalArgumentException("파일명이 없습니다.");
            try(InputStream input=part.getInputStream()){if(name.toLowerCase(Locale.ROOT).endsWith(".zip"))batch.zip(input);else batch.add(name,input);}
        }
        if(batch.items.isEmpty())throw new IllegalArgumentException("상품 JPG 이미지 또는 ZIP 파일을 선택하세요.");return batch.items;
    }
    void zip(InputStream input)throws IOException{
        int before=items.size();
        try(ZipInputStream zip=new ZipInputStream(input,java.nio.charset.StandardCharsets.UTF_8)){ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){if(++entries>300)throw new IllegalArgumentException("ZIP 항목은 최대 300개입니다.");String name=entry.getName();
                if(name.startsWith("/")||name.contains("\\")||name.contains(":")||Arrays.asList(name.split("/")).contains(".."))throw new IllegalArgumentException("안전하지 않은 ZIP 경로입니다.");
                if(entry.isDirectory()){if(zip.read()!=-1)throw new IllegalArgumentException("ZIP 폴더 항목에 파일 데이터가 있습니다.");continue;}add(name.substring(name.lastIndexOf('/')+1),zip);
            }
        }
        if(items.size()==before)throw new IllegalArgumentException("ZIP에 JPG 이미지가 없습니다.");
    }
    void add(String name,InputStream input)throws IOException{
        if(items.size()>=100)throw new IllegalArgumentException("이미지는 한 번에 최대 100개입니다.");
        Matcher match=Pattern.compile("([A-Za-z0-9_-]{1,30})_([1-6])\\.(?i:jpg)").matcher(name);
        if(!match.matches())throw new IllegalArgumentException("파일명은 상품코드_1.JPG ~ 상품코드_6.JPG 형식이어야 합니다: "+name);
        String code=match.group(1);int seq=Integer.parseInt(match.group(2));if(!names.add((code+"_"+seq).toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("중복 이미지 파일입니다: "+name);
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=input.read(buffer))!=-1){total+=n;if(out.size()+n>5242880||total>33554432)throw new IllegalArgumentException("이미지는 각 5MB, 압축 해제 전체 32MB 이하입니다.");out.write(buffer,0,n);}
        byte[] raw=out.toByteArray();if(raw.length<3||(raw[0]&255)!=255||(raw[1]&255)!=216||(raw[2]&255)!=255)throw new IllegalArgumentException("실제 JPG 파일이 아닙니다: "+name);
        byte[] jpeg;
        try{jpeg=ImageFiles.jpeg(raw);}catch(IOException e){throw new IllegalArgumentException("JPG 파일을 읽을 수 없습니다: "+name);}
        converted+=jpeg.length;if(converted>33554432)throw new IllegalArgumentException("변환된 이미지 전체는 32MB 이하이어야 합니다.");items.add(new Item(code,seq,jpeg));
    }
    List<Item> items(){return items;}
}