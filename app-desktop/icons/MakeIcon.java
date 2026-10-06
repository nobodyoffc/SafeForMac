import javax.imageio.ImageIO; import java.awt.*; import java.awt.font.*; import java.awt.geom.*; import java.awt.image.BufferedImage; import java.io.File;
/** Android Safe's launcher icon (Material Blue 500, "Safe" in #DDDDDD bold) redrawn on the macOS icon grid. */
public class MakeIcon { public static void main(String[] a) throws Exception {
  int n = 1024, inset = 100, side = n - 2 * inset; double radius = 185;
  BufferedImage im = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
  Graphics2D g = im.createGraphics();
  g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
  g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
  g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
  // Soft drop shadow, as macOS app icons carry.
  for (int i = 12; i > 0; i--) { g.setColor(new Color(0, 0, 0, 4)); g.fill(new RoundRectangle2D.Double(inset - i / 2.0, inset + 10 - i / 2.0 + 6, side + i, side + i, (radius + i) * 2, (radius + i) * 2)); }
  Shape body = new RoundRectangle2D.Double(inset, inset, side, side, radius * 2, radius * 2);
  g.setColor(new Color(0x2196F3)); g.fill(body);
  // Text sized like Android's: 77% of the tile's width, centred.
  Font f = new Font("Helvetica Neue", Font.BOLD, 100);
  GlyphVector gv = f.createGlyphVector(g.getFontRenderContext(), "Safe");
  Rectangle2D b = gv.getVisualBounds();
  double scale = side * 0.77 / b.getWidth();
  AffineTransform t = new AffineTransform();
  t.translate(n / 2.0 - b.getCenterX() * scale, n / 2.0 - b.getCenterY() * scale);
  t.scale(scale, scale);
  g.setColor(new Color(0xDDDDDD)); g.fill(t.createTransformedShape(gv.getOutline()));
  g.dispose();
  ImageIO.write(im, "png", new File(a[0]));
  System.out.println("font: " + f.getFontName());
}}
