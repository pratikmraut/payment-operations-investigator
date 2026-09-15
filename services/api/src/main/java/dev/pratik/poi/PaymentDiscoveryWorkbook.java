package dev.pratik.poi;

import java.io.*;
import java.math.*;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.*;

/** Bounded, dependency-free reader for the Find payment XLSX interchange. */
public final class PaymentDiscoveryWorkbook {
  public static final int MAX_BYTES = 5 * 1024 * 1024;
  private static final int MAX_EXPANDED = 20 * 1024 * 1024, MAX_ENTRIES = 256, MAX_ROWS = 2000;
  private static final List<String> REQUIRED = List.of("PIO_REF_TXN_NO", "PIO_ORG_BRN", "PIO_ORG_BANK",
      "REF_SUBSEQ_NO", "UTR_REF_NO", "DATINITIATION", "NUMAMOUNT_4038");
  private static final Set<String> OPTIONAL = Set.of("CURRENCY", "DIRECTION");
  private static final Set<String> ORDINAL_HEADERS = Set.of("", "#", "ROW_NUMBER", "ROWNUM", "ROW_NO", "S.NO", "S.NO.");
  private static final Set<String> INSTRUCTIONS = Set.of("notes", "instructions", "readme");
  private static final Pattern CELL = Pattern.compile("([A-Z]{1,3})([1-9][0-9]{0,6})");
  private record Value(String text, String type, int style) { }
  private record Row(int number, Map<Integer, Value> cells) { }
  private record Relationship(String type, String target) { }
  private PaymentDiscoveryWorkbook() { }

  public static List<Map<String, String>> read(byte[] bytes) {
    return readWorkbook(bytes, null);
  }

  /** Separate exact-text evidence path; discovery's numeric/date conversion is not used. */
  static List<Map<String, String>> readColumns(byte[] bytes, List<String> columns) {
    return readWorkbook(bytes, List.copyOf(columns));
  }

  private static List<Map<String, String>> readWorkbook(byte[] bytes, List<String> columns) {
    boolean evidence = columns != null;
    int maxCellLength = evidence ? CaseEvidenceSchema.MAX_CELL_LENGTH : 2048;
    if (bytes == null || bytes.length == 0) throw invalid("Upload an .xlsx workbook containing a Payments sheet.");
    if (bytes.length > MAX_BYTES) throw limit("The Excel file must be no larger than 5 MiB.");
    Map<String, byte[]> parts = unzip(bytes);
    Document types = xml(part(parts, "[Content_Types].xml"));
    for (Element entry : children(types.getDocumentElement())) {
      String contentType = entry.getAttribute("ContentType").toLowerCase(Locale.ROOT);
      if (contentType.contains("macro") || contentType.contains("vba") || contentType.contains("activex"))
        throw invalid("Macros and active content are unsupported. Export a plain .xlsx workbook.");
    }
    // Validate every relationship part, including links on ignored instruction sheets.
    for (String path : parts.keySet()) if (path.endsWith(".rels")) relationships(parts.get(path), relationshipBase(path));
    Map<String, Relationship> root = relationships(part(parts, "_rels/.rels"), "");
    List<String> workbooks = root.values().stream().filter(r -> r.type.endsWith("/officeDocument")).map(Relationship::target).toList();
    if (workbooks.size() != 1) throw invalid("The XLSX workbook relationship is missing or ambiguous.");
    String workbookPath = workbooks.get(0);
    Document workbook = xml(part(parts, workbookPath));
    if (!"workbook".equals(workbook.getDocumentElement().getLocalName())) throw invalid("The workbook XML is invalid.");
    String directory = parent(workbookPath);
    String filename = workbookPath.substring(directory.length());
    Map<String, Relationship> rels = relationships(part(parts, directory + "_rels/" + filename + ".rels"), directory);
    boolean date1904 = false;
    for (Element property : descendants(workbook.getDocumentElement(), "workbookPr")) {
      String flag = property.getAttribute("date1904");
      if (!Set.of("", "0", "1", "true", "false").contains(flag)) throw invalid("The Excel date system is invalid.");
      date1904 = flag.equals("1") || flag.equals("true");
    }
    List<String> strings = sharedStrings(parts, rels, maxCellLength);
    List<Boolean> dates = dateStyles(parts, rels);
    List<Row> chosen = null;
    for (Element sheet : descendants(workbook.getDocumentElement(), "sheet")) {
      String id = "";
      NamedNodeMap attributes = sheet.getAttributes();
      for (int i = 0; i < attributes.getLength(); i++) {
        Node attribute = attributes.item(i);
        if ("id".equals(attribute.getLocalName()) && attribute.getNamespaceURI() != null
            && attribute.getNamespaceURI().endsWith("/relationships")) id = attribute.getNodeValue();
      }
      Relationship relation = rels.get(id);
      if (relation == null || !relation.type.endsWith("/worksheet")) throw invalid("Only ordinary Excel worksheets are supported.");
      if (!evidence && INSTRUCTIONS.contains(sheet.getAttribute("name").strip().toLowerCase(Locale.ROOT))) {
        xml(part(parts, relation.target)); // Ignored content still cannot carry a DTD or entity declaration.
        continue;
      }
      List<Row> rows = sheetRows(part(parts, relation.target), strings, evidence ? 64 : 32,
          maxCellLength, evidence ? 500 : MAX_ROWS, evidence);
      if (rows.isEmpty()) continue;
      if (chosen != null) throw invalid("Keep one populated payment worksheet. Move instructions to a Notes sheet.");
      chosen = rows;
    }
    if (chosen == null) throw invalid("No populated payment worksheet was found.");
    return evidence ? evidenceRows(chosen, columns) : paymentRows(chosen, dates, date1904);
  }

  private static Map<String, byte[]> unzip(byte[] bytes) {
    int expected = zipEntryCount(bytes);
    Map<String, byte[]> parts = new LinkedHashMap<>();
    Set<String> names = new HashSet<>();
    int total = 0, count = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      byte[] buffer = new byte[8192];
      while ((entry = zip.getNextEntry()) != null) {
        if (++count > MAX_ENTRIES) throw limit("The Excel archive contains too many parts.");
        String name = entry.getName();
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")
            || Arrays.asList(name.split("/", -1)).contains("..") || name.chars().anyMatch(Character::isISOControl))
          throw invalid("The Excel archive contains an unsafe part path.");
        if (!names.add(name.toLowerCase(Locale.ROOT))) throw invalid("The Excel archive contains duplicate parts.");
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("vbaproject") || lower.contains("macrosheet") || lower.contains("activex/"))
          throw invalid("Macros and active content are unsupported. Export a plain .xlsx workbook.");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int read;
        while ((read = zip.read(buffer)) != -1) {
          total += read;
          if (total > MAX_EXPANDED) throw limit("The expanded Excel archive exceeds 20 MiB.");
          output.write(buffer, 0, read);
        }
        if (!entry.isDirectory()) parts.put(name, output.toByteArray());
        zip.closeEntry();
      }
    } catch (IOException ex) { throw invalid("The XLSX archive is corrupt or unsupported. Save it again as .xlsx."); }
    if (count != expected) throw invalid("The XLSX archive directory does not match its contents.");
    return parts;
  }

  private static int zipEntryCount(byte[] bytes) {
    for (int p = bytes.length - 22; p >= Math.max(0, bytes.length - 65557); p--) {
      if (u32(bytes, p) != 0x06054b50L || p + 22 + u16(bytes, p + 20) != bytes.length) continue;
      if (u16(bytes, p + 4) != 0 || u16(bytes, p + 6) != 0 || u16(bytes, p + 8) != u16(bytes, p + 10))
        throw invalid("Multipart Excel archives are unsupported.");
      int count = u16(bytes, p + 10);
      if (count > MAX_ENTRIES) throw limit("The Excel archive contains too many parts.");
      long size = u32(bytes, p + 12), start = u32(bytes, p + 16);
      if (count == 0 || start + size != p || start > Integer.MAX_VALUE || u32(bytes, (int) start) != 0x02014b50L)
        throw invalid("The XLSX archive directory is invalid.");
      return count;
    }
    throw invalid("The file is not a complete .xlsx ZIP archive.");
  }
  private static int u16(byte[] bytes, int p) { return p < 0 || p + 2 > bytes.length ? -1 : (bytes[p] & 255) | (bytes[p + 1] & 255) << 8; }
  private static long u32(byte[] bytes, int p) { return p < 0 || p + 4 > bytes.length ? -1 : (u16(bytes, p) & 65535L) | (u16(bytes, p + 2) & 65535L) << 16; }

  private static Document xml(byte[] bytes) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "128");
      var builder = factory.newDocumentBuilder();
      builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entities are unsupported"); });
      builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
        @Override public void error(SAXParseException e) throws SAXException { throw e; }
        @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
      });
      return builder.parse(new ByteArrayInputStream(bytes));
    } catch (Exception ex) { throw invalid("The Excel XML is invalid or contains unsupported entities."); }
  }

  private static Map<String, Relationship> relationships(byte[] bytes, String base) {
    Map<String, Relationship> result = new LinkedHashMap<>();
    Document doc = xml(bytes);
    if (!"Relationships".equals(doc.getDocumentElement().getLocalName())) throw invalid("The Excel relationships are invalid.");
    for (Element entry : children(doc.getDocumentElement())) {
      if (!"Relationship".equals(entry.getLocalName())) throw invalid("The Excel relationships are invalid.");
      if (!entry.getAttribute("TargetMode").isEmpty() && !entry.getAttribute("TargetMode").equals("Internal"))
        throw invalid("External workbook links are unsupported. Remove linked content before uploading.");
      String id = entry.getAttribute("Id"), type = entry.getAttribute("Type");
      String target = resolve(base, entry.getAttribute("Target"));
      if (id.isBlank() || type.isBlank() || result.putIfAbsent(id, new Relationship(type, target)) != null)
        throw invalid("The Excel relationships are missing or duplicated.");
    }
    return result;
  }
  private static String resolve(String base, String target) {
    try {
      URI uri = new URI(target);
      if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
        throw invalid("External or ambiguous workbook links are unsupported.");
      String value = uri.getPath();
      if (value == null || value.isBlank() || value.contains("\\") || value.contains(":") || value.chars().anyMatch(Character::isISOControl))
        throw invalid("The Excel relationship target is invalid.");
      Deque<String> segments = new ArrayDeque<>();
      for (String segment : (value.startsWith("/") ? value.substring(1) : base + value).split("/")) {
        if (segment.isEmpty() || segment.equals(".")) continue;
        if (segment.equals("..")) {
          if (segments.isEmpty()) throw invalid("An Excel relationship escapes the workbook archive.");
          segments.removeLast();
        } else segments.addLast(segment);
      }
      if (segments.isEmpty()) throw invalid("The Excel relationship target is invalid.");
      return String.join("/", segments);
    } catch (java.net.URISyntaxException ex) { throw invalid("The Excel relationship target is invalid."); }
  }
  private static String relationshipBase(String path) {
    if (path.equals("_rels/.rels")) return "";
    int separator = path.lastIndexOf("/_rels/");
    if (separator < 0) throw invalid("The Excel relationship part has an invalid location.");
    return path.substring(0, separator + 1);
  }
  private static String parent(String path) { return path.substring(0, path.lastIndexOf('/') + 1); }
  private static byte[] part(Map<String, byte[]> parts, String name) {
    byte[] result = parts.get(name);
    if (result == null) throw invalid("The XLSX archive is missing a required workbook part.");
    return result;
  }
  private static List<String> sharedStrings(Map<String, byte[]> parts, Map<String, Relationship> rels, int maxCellLength) {
    List<String> result = new ArrayList<>();
    for (Relationship relation : rels.values()) if (relation.type.endsWith("/sharedStrings")) {
      if (!result.isEmpty()) throw invalid("Multiple shared-string tables are unsupported.");
      for (Element item : descendants(xml(part(parts, relation.target)).getDocumentElement(), "si")) {
        if (result.size() >= 25000) throw limit("The workbook contains too many shared strings.");
        result.add(textRuns(item, maxCellLength));
      }
    }
    return result;
  }
  private static List<Boolean> dateStyles(Map<String, byte[]> parts, Map<String, Relationship> rels) {
    List<Boolean> result = new ArrayList<>();
    for (Relationship relation : rels.values()) if (relation.type.endsWith("/styles")) {
      Element root = xml(part(parts, relation.target)).getDocumentElement();
      Map<Integer, String> formats = new HashMap<>();
      for (Element format : descendants(root, "numFmt")) formats.put(integer(format.getAttribute("numFmtId")), format.getAttribute("formatCode"));
      for (Element xfs : children(root)) if ("cellXfs".equals(xfs.getLocalName())) for (Element xf : children(xfs)) {
        if (result.size() >= 10000) throw limit("The workbook contains too many cell styles.");
        int id = xf.hasAttribute("numFmtId") ? integer(xf.getAttribute("numFmtId")) : 0;
        String format = formats.getOrDefault(id, "").replaceAll("\"[^\"]*\"|\\\\.|\\[[^]]*\\]|_.|\\*.", "");
        result.add((id >= 14 && id <= 22) || (id >= 27 && id <= 36) || (id >= 45 && id <= 47)
            || (id >= 50 && id <= 58) || format.toLowerCase(Locale.ROOT).matches(".*[ymdhs].*"));
      }
    }
    return result;
  }

  private static List<Row> sheetRows(byte[] bytes, List<String> strings, int maxColumns,
      int maxCellLength, int maxRows, boolean evidence) {
    Element root = xml(bytes).getDocumentElement();
    if (!"worksheet".equals(root.getLocalName())) throw invalid("The selected Excel part is not a worksheet.");
    if (!descendants(root, "f").isEmpty()) throw invalid("Payment worksheets cannot contain formulas. Paste values before uploading.");
    List<Row> rows = new ArrayList<>();
    Set<Integer> rowNumbers = new HashSet<>();
    int cellCount = 0, rowCount = 0;
    for (Element data : children(root)) if ("sheetData".equals(data.getLocalName())) for (Element row : children(data)) {
      if (!"row".equals(row.getLocalName())) throw invalid("The Excel row structure is invalid.");
      if (++rowCount > 10000) throw limit("The worksheet contains too many rows, including empty rows.");
      int number = row.hasAttribute("r") ? integer(row.getAttribute("r")) : rowCount;
      if (number < 1 || number > 1048576 || !rowNumbers.add(number)) throw invalid("The worksheet has invalid or duplicate row numbers.");
      Map<Integer, Value> cells = new TreeMap<>();
      for (Element cell : children(row)) {
        if (!"c".equals(cell.getLocalName())) continue;
        if (++cellCount > 50000) throw limit("The payment worksheet contains too many cells.");
        var address = CELL.matcher(cell.getAttribute("r"));
        if (!address.matches() || integer(address.group(2)) != number) throw invalid("A worksheet cell address is invalid.");
        int column = 0; for (char letter : address.group(1).toCharArray()) column = column * 26 + letter - 'A' + 1;
        if (column > maxColumns) throw invalid("Keep only the supported payment columns in the data worksheet.");
        String type = cell.getAttribute("t"), text = "";
        List<Element> values = children(cell).stream().filter(e -> "v".equals(e.getLocalName())).toList();
        if (values.size() > 1) throw invalid("A worksheet cell contains duplicate values.");
        if (type.equals("inlineStr")) text = textRuns(cell, maxCellLength);
        else if (!values.isEmpty()) text = values.get(0).getTextContent();
        if (type.equals("s") && !text.isBlank()) {
          int index = integer(text);
          if (index >= strings.size()) throw invalid("A shared-string reference is invalid.");
          text = strings.get(index);
        }
        if (text.length() > maxCellLength) throw limit("A payment cell exceeds " + maxCellLength + " characters.");
        if (evidence && !Set.of("", "n", "s", "inlineStr", "str").contains(type))
          throw invalid("Evidence cells must be Text; boolean, error and Excel date cells are unsupported.");
        if (cells.putIfAbsent(column, new Value(text, type, cell.hasAttribute("s") ? integer(cell.getAttribute("s")) : 0)) != null)
          throw invalid("The worksheet contains duplicate cell addresses.");
      }
      if (cells.values().stream().anyMatch(value -> !value.text.isBlank())) rows.add(new Row(number, cells));
      // Evidence's optional display-ordinal-only rows are removed after its headers are known.
      // Archive bytes, XML rows and cell counts remain bounded here; actual source rows are bounded below.
      if (!evidence && rows.size() > maxRows + 1) throw limit("Upload no more than " + maxRows + " payment rows at a time.");
    }
    rows.sort(Comparator.comparingInt(Row::number));
    return rows;
  }

  private static List<Map<String, String>> evidenceRows(List<Row> rows, List<String> columns) {
    Row header = rows.get(0);
    int first = header.cells.entrySet().stream().filter(e -> !e.getValue().text.isBlank())
        .mapToInt(Map.Entry::getKey).min().orElse(1);
    if (first == 1 && ORDINAL_HEADERS.contains(header.cells.get(1).text.strip().toUpperCase(Locale.ROOT))) first = 2;
    if (first != 1 && first != 2) throw invalid("Evidence headers must begin in A, or B after one display-ordinal column.");
    int end = first + columns.size();
    for (int index = 0; index < columns.size(); index++) {
      Value cell = header.cells.get(first + index);
      if (cell == null || !isText(cell) || !columns.get(index).equals(cell.text))
        throw invalid("Use every standard evidence header in the template order; mismatch at column " + (first + index) + ".");
    }
    for (var cell : header.cells.entrySet()) if (cell.getKey() >= end && !cell.getValue().text.isBlank())
      throw invalid("The evidence worksheet contains an extra header.");
    List<Map<String, String>> result = new ArrayList<>();
    for (Row row : rows.subList(1, rows.size())) {
      for (var entry : row.cells.entrySet()) {
        Value cell = entry.getValue();
        if (first == 2 && entry.getKey() == 1) {
          if (!cell.text.isEmpty() && !cell.text.matches("[0-9]{1,38}"))
            throw invalid("Row " + row.number + ": display ordinal must be a nonnegative whole number.");
        } else {
          if (!cell.text.isEmpty() && !isText(cell))
            throw invalid("Row " + row.number + ": evidence values must be Text. Re-export numeric cells from the source to preserve exact values.");
          if (entry.getKey() >= end && !cell.text.isEmpty())
            throw invalid("Row " + row.number + " contains data outside the standard evidence headers.");
        }
      }
      Map<String, String> values = new LinkedHashMap<>();
      for (int index = 0; index < columns.size(); index++)
        values.put(columns.get(index), row.cells.getOrDefault(first + index, new Value("", "inlineStr", 0)).text);
      if (values.values().stream().allMatch(String::isBlank)) continue;
      result.add(Collections.unmodifiableMap(values));
      if (result.size() > CaseEvidenceSchema.MAX_ROWS) throw limit("Upload no more than 500 evidence source rows at a time.");
    }
    return List.copyOf(result);
  }

  private static List<Map<String, String>> paymentRows(List<Row> rows, List<Boolean> dates, boolean date1904) {
    Row header = rows.get(0);
    int first = header.cells.entrySet().stream().filter(e -> !e.getValue().text.isBlank()).mapToInt(Map.Entry::getKey).min().orElse(1);
    boolean ordinal = first == 2;
    if (first == 1 && ORDINAL_HEADERS.contains(header.cells.get(1).text.strip().toUpperCase(Locale.ROOT))) { ordinal = true; first = 2; }
    if (first != 1 && first != 2) throw invalid("Headers must begin in column A, or B after one optional row-number column.");
    Map<Integer, String> headers = new TreeMap<>();
    Set<String> seen = new HashSet<>();
    int last = header.cells.entrySet().stream().filter(e -> !e.getValue().text.isBlank()).mapToInt(Map.Entry::getKey).max().orElse(first);
    for (int column = first; column <= last; column++) {
      Value cell = header.cells.get(column);
      String name = cell == null ? "" : cell.text;
      if (cell == null || !isText(cell) || (!REQUIRED.contains(name) && !OPTIONAL.contains(name)))
        throw invalid("Use the exact native headers from the downloadable template; unknown or blank header at column " + column + ".");
      if (!seen.add(name)) throw invalid("The payment worksheet contains a duplicate header.");
      headers.put(column, name);
    }
    if (!seen.containsAll(REQUIRED)) throw invalid("The worksheet must include all seven required native payment headers from the template.");
    List<Map<String, String>> result = new ArrayList<>();
    for (Row row : rows.subList(1, rows.size())) {
      for (Map.Entry<Integer, Value> cell : row.cells.entrySet()) {
        if (ordinal && cell.getKey() == 1) {
          if (!cell.getValue().text.isBlank()) whole(cell.getValue().text, row.number, "row number");
        } else if (!headers.containsKey(cell.getKey()) && !cell.getValue().text.isBlank())
          throw invalid("Row " + row.number + " contains data outside the supported headers.");
      }
      Map<String, String> values = new LinkedHashMap<>();
      for (Map.Entry<Integer, String> entry : headers.entrySet()) {
        Value cell = row.cells.getOrDefault(entry.getKey(), new Value("", "inlineStr", 0));
        String name = entry.getValue();
        if (!Set.of("", "n", "s", "inlineStr", "str", "d").contains(cell.type))
          throw invalid("Row " + row.number + ", " + name + ": boolean and error cells are unsupported.");
        String value = cell.text;
        if (!value.isBlank()) {
          if (name.equals("PIO_REF_TXN_NO") || name.equals("UTR_REF_NO")) {
            if (!isText(cell)) throw invalid("Row " + row.number + ", " + name + ": store identifiers as Text and re-export them from the source; Excel numeric references may have lost digits.");
          } else if (Set.of("PIO_ORG_BRN", "PIO_ORG_BANK", "REF_SUBSEQ_NO").contains(name)) value = whole(value, row.number, name);
          else if (name.equals("NUMAMOUNT_4038")) value = decimal(value, row.number, name).toPlainString();
          else if (name.equals("DATINITIATION")) value = date(cell, dates, date1904, row.number);
          else if (!isText(cell)) throw invalid("Row " + row.number + ", " + name + ": use a text cell.");
        }
        values.put(name, value);
      }
      if (values.values().stream().allMatch(String::isBlank)) continue;
      if (values.get("PIO_REF_TXN_NO").isBlank()) throw invalid("Row " + row.number + ": PIO_REF_TXN_NO is required.");
      result.add(Collections.unmodifiableMap(values));
    }
    if (result.isEmpty()) throw invalid("The worksheet contains headers but no payment rows.");
    return List.copyOf(result);
  }
  private static String whole(String value, int row, String name) {
    BigDecimal number = decimal(value, row, name);
    try {
      BigInteger integer = number.toBigIntegerExact();
      if (integer.signum() < 0 || integer.toString().length() > 38) throw new ArithmeticException();
      return integer.toString();
    } catch (ArithmeticException ex) { throw invalid("Row " + row + ", " + name + ": use a nonnegative whole number."); }
  }
  private static BigDecimal decimal(String value, int row, String name) {
    try {
      if (value.length() > 100 || !value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]{1,3})?")) throw new NumberFormatException();
      BigDecimal result = new BigDecimal(value);
      if (result.precision() > 38 || Math.abs(result.scale()) > 38) throw new NumberFormatException();
      return result;
    } catch (NumberFormatException ex) { throw invalid("Row " + row + ", " + name + ": use an exact decimal with a dot and no separators, up to 38 digits."); }
  }
  private static String date(Value cell, List<Boolean> styles, boolean date1904, int row) {
    try {
      if (isText(cell) || cell.type.equals("d")) {
        if (cell.text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?"))
          return LocalDateTime.parse(cell.text).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        throw new DateTimeException("Not ISO local date/time");
      }
      if (cell.style >= styles.size() || !styles.get(cell.style))
        throw invalid("Row " + row + ", DATINITIATION: use an Excel date/time cell or ISO text YYYY-MM-DDTHH:mm:ss. Include the source time.");
      BigDecimal serial = decimal(cell.text, row, "DATINITIATION");
      if (serial.signum() < 0 || serial.compareTo(new BigDecimal("2958466")) >= 0) throw new DateTimeException("Out of range");
      long days = serial.longValue();
      if (!date1904 && days == 60) throw invalid("Excel's fictional 1900-02-29 cannot be imported. Correct DATINITIATION at the source.");
      // Excel serials are floating-point dates; normalize their fractional day to the nearest second.
      long seconds = serial.subtract(BigDecimal.valueOf(days)).multiply(BigDecimal.valueOf(86400)).setScale(0, RoundingMode.HALF_UP).longValueExact();
      LocalDate base = date1904 ? LocalDate.of(1904, 1, 1) : LocalDate.of(1899, 12, 31);
      LocalDateTime value = base.plusDays(days - (!date1904 && days > 60 ? 1 : 0)).atStartOfDay().plusSeconds(seconds);
      if (value.getYear() > 9999) throw new DateTimeException("Out of range");
      return value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    } catch (DateTimeException | ArithmeticException ex) {
      throw invalid("Row " + row + ", DATINITIATION: use a valid Excel date/time or ISO text YYYY-MM-DDTHH:mm:ss. Date-only text cannot establish the source time.");
    }
  }
  private static boolean isText(Value value) { return Set.of("s", "inlineStr", "str").contains(value.type); }
  private static int integer(String value) {
    try { int result = Integer.parseInt(value); if (result < 0) throw new NumberFormatException(); return result; }
    catch (NumberFormatException ex) { throw invalid("An Excel index or style reference is invalid."); }
  }
  private static String textRuns(Element element, int maxCellLength) {
    StringBuilder text = new StringBuilder();
    for (Element run : descendants(element, "t")) {
      boolean phonetic = false;
      for (Node ancestor = run.getParentNode(); ancestor != null && ancestor != element; ancestor = ancestor.getParentNode())
        if (ancestor instanceof Element parent && "rPh".equals(parent.getLocalName())) phonetic = true;
      if (phonetic) continue;
      text.append(run.getTextContent());
      if (text.length() > maxCellLength) throw limit("An Excel text value exceeds " + maxCellLength + " characters.");
    }
    return text.toString();
  }
  private static List<Element> children(Element element) {
    List<Element> result = new ArrayList<>();
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) if (child instanceof Element value) result.add(value);
    return result;
  }
  private static List<Element> descendants(Element element, String local) {
    List<Element> result = new ArrayList<>();
    NodeList nodes = element.getElementsByTagNameNS("*", local);
    for (int i = 0; i < nodes.getLength(); i++) result.add((Element) nodes.item(i));
    return result;
  }
  private static ApiException invalid(String message) { return new ApiException(422, "INVALID_DISCOVERY_WORKBOOK", message); }
  private static ApiException limit(String message) { return new ApiException(413, "DISCOVERY_WORKBOOK_TOO_LARGE", message); }
}
