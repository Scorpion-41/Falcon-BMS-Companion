// Prints, for every page of the given PDFs, the direction its text is printed in, so charts.mjs can turn sideways
// pages upright.
//
// AIP charts often draw a landscape table on a portrait page: the page is not rotated (/Rotate 0), the text is.
// An image can't tell that apart from an ordinary table — both draw long rules in both directions — but PDFBox knows
// the angle of every glyph.
//
// The angle that matters is the one in the MIDDLE of the page. The frame around it (the "A I P / Republic of Korea"
// header, the chart number, the "OFFICE OF CIVIL AVIATION" footer) is always printed upright, and on a page whose
// table is small it outnumbers the table's own text, so a vote over the whole page says "upright" and the table stays
// on its side. The frame lives in the margins; what the pilot reads is in the middle.
//
// The page's own /Rotate has to come off the answer. PDFBox applies it when it renders the page, but reports glyph
// angles in the page's own space, so an Argentine chart drawn upright on a page rotated 270 comes back as "upright"
// while the image it renders is on its side. The turn a rendered page needs is (glyph angle - page rotation).
//
// Usage (JDK 17+ runs the source directly):
//   java -cp tools/extractor/cache/pdfbox-app.jar tools/extractor/src/PageDirs.java <file.pdf> [more.pdf …]
// Output, one line per page:
//   <pdf path>\t<page>\t<turn the rendered page needs, 0|90|180|270>\t<characters counted in the middle>\t<page /Rotate>
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

public class PageDirs extends PDFTextStripper {
    /** The part of the page that is content rather than frame. */
    private static final double SIDE = 0.08, TOP = 0.12;

    private final Map<Integer, Integer> whole = new HashMap<>(); // rounded angle -> characters
    private final Map<Integer, Integer> middle = new HashMap<>();

    public PageDirs() throws Exception { super(); }

    @Override
    protected void processTextPosition(TextPosition text) {
        String unicode = text.getUnicode();
        int chars = unicode == null ? 0 : unicode.trim().length();
        if (chars == 0) return;

        // the glyph's own angle: getDir() only reports the page direction, and one page can hold an upright header
        // above a table that is drawn sideways
        var m = text.getTextMatrix();
        double angle = Math.toDegrees(Math.atan2(m.getValue(0, 1), m.getValue(0, 0)));
        int dir = (((int) Math.round(angle / 90.0) * 90) % 360 + 360) % 360;
        whole.merge(dir, chars, Integer::sum);

        // XDirAdj/YDirAdj are already in the page's own upright space, with the origin top left
        PDRectangle box = getCurrentPage().getCropBox();
        int rotation = ((getCurrentPage().getRotation() % 360) + 360) % 360;
        boolean turned = rotation == 90 || rotation == 270;
        float width = turned ? box.getHeight() : box.getWidth();
        float height = turned ? box.getWidth() : box.getHeight();
        float x = text.getXDirAdj(), y = text.getYDirAdj();
        boolean inMiddle = x > width * SIDE && x < width * (1 - SIDE) && y > height * TOP && y < height * (1 - TOP);
        if (inMiddle) middle.merge(dir, chars, Integer::sum);
    }

    private static int dominant(Map<Integer, Integer> counts) {
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
    }

    private static int total(Map<Integer, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public static void main(String[] args) {
        for (String path : args) {
            try (PDDocument doc = Loader.loadPDF(new File(path))) {
                for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                    PageDirs stripper = new PageDirs();
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    stripper.writeText(doc, new StringWriter());
                    int rotation = ((doc.getPage(page - 1).getRotation() % 360) + 360) % 360;
                    int turn = ((dominant(stripper.middle) - rotation) % 360 + 360) % 360;
                    System.out.println(path + "\t" + page + "\t" + turn + "\t" + total(stripper.middle) + "\t" + rotation);
                }
            } catch (Exception e) {
                System.err.println("failed " + path + ": " + e);
            }
        }
    }
}
