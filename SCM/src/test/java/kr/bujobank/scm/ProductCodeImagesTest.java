package kr.bujobank.scm;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class ProductCodeImagesTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void discoversFilesAddedAndReplacedWithoutRestart() throws Exception {
        ProductCodeImages images=new ProductCodeImages(temp.getRoot().toString());
        assertFalse(images.exists("ITEM-01"));
        Path file=temp.getRoot().toPath().resolve("ITEM-01_1.JPG");
        byte[] first={(byte)255,(byte)216,(byte)255,1}, second={(byte)255,(byte)216,(byte)255,2};
        Files.write(file,first); assertTrue(images.exists("ITEM-01")); assertArrayEquals(first,images.read("ITEM-01"));
        Files.write(file,second); assertArrayEquals(second,images.read("ITEM-01"));
        Files.delete(file); assertFalse(images.exists("ITEM-01"));
    }
    @Test public void disabledAndUnsafeCodesAreNotResolved() {
        assertFalse(new ProductCodeImages("").exists("ITEM"));
        ProductCodeImages images=new ProductCodeImages(temp.getRoot().toString());
        assertFalse(images.exists("../secret")); assertFalse(images.exists("a/b")); assertFalse(images.exists(null));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsRelativeDirectory() { new ProductCodeImages("uploadfiles/images"); }
    @Test(expected=IOException.class) public void rejectsNonJpegContent() throws Exception {
        Files.write(temp.getRoot().toPath().resolve("ITEM_1.JPG"),new byte[]{1,2,3});
        new ProductCodeImages(temp.getRoot().toString()).read("ITEM");
    }
    @Test public void replacesRenamesAndRollsBackNumberedFiles() throws Exception {
        ProductCodeImages images=new ProductCodeImages(temp.getRoot().toString());
        byte[] jpeg={(byte)255,(byte)216,(byte)255,1};
        try(ProductCodeImages.Change files=images.change()){files.replace(null,"ABC",java.util.Arrays.asList(jpeg,jpeg));files.commit();}
        assertEquals(java.util.Arrays.asList(1,2),images.sequences("ABC"));
        try(ProductCodeImages.Change files=images.change()){files.replace("ABC","ABC",java.util.Collections.emptyList());}
        assertArrayEquals(jpeg,images.read("ABC",2));
        try(ProductCodeImages.Change files=images.change()){files.rename("ABC","XYZ");files.commit();}
        assertFalse(images.exists("ABC"));assertArrayEquals(jpeg,images.read("XYZ",1));
        try(ProductCodeImages.Change files=images.change()){files.replace("XYZ","XYZ",java.util.Collections.emptyList());files.commit();}
        assertFalse(images.exists("XYZ"));
    }
    @Test public void convertsTransparentPngToActualJpeg() throws Exception {
        java.awt.image.BufferedImage png=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.io.ByteArrayOutputStream input=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(png,"PNG",input);
        byte[] jpeg=ImageFiles.jpeg(input.toByteArray());
        assertEquals(255,jpeg[0]&255);assertEquals(216,jpeg[1]&255);
        java.awt.image.BufferedImage decoded=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
        assertEquals(0xffffff,decoded.getRGB(0,0)&0xffffff);
    }}
