package no.petroware.logio.json;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.stream.JsonParser;

import no.petroware.logio.common.Statistics;
import no.petroware.logio.util.Util;

/**
 * Class for reading well logs in the JSON format.
 * * <p>
 * Typical usage:
 *
 * <pre>
 *   JsonReader reader = new JsonReader(new File("path/to/file");
 *   List&lt;JsonFile&gt; jsonFiles = reader.read(true, true, null);
 * </pre>
 * If the curve data is not needed, it is possible to read only the
 * meta-data from the disk file. The curve data may be filled in later:
 * <br>
 * <br>
 * <pre>
 *   JsonReader reader = new JsonReader(new File("path/to/file"));
 *   List&lt;JsonFile&gt; jsonFiles = reader.read(false, false, null);
 *   :
 *   reader.readData(jsonFiles);
 * </pre>
 *
 * Note that even if only meta-data is read, all curve information
 * are properly established as this information comes from metadata.
 * Only the curve <em>values</em> will be missing.
 * <p>
 * If disk files are larger than physical memory, it is possible to
 * <em>stream</em> (process than throw away) the data during read.
 * See {@link JsonDataListener}.
 *
 * @author <a href="mailto:info@petroware.no">Petroware AS</a>
 */
public final class JsonReader
{
  /** The logger instance. */
  private static final Logger logger_ = Logger.getLogger(JsonReader.class.getName());

  /** The file to read. Null if read directly from stream. */
  private final File file_;

  /** The stream to be read. Null if read from file. */
  private final InputStream inputStream_;

  /**
   * Create a JSON reader for the specified file instance.
   * The actual reading is done with the read() or readData() methods.
   *
   * @param file  File to read. Non-null.
   * @throws IllegalArgumentException  If file is null.
   * @see #read
   * @see #readData
   */
  public JsonReader(File file)
  {
    file_ = file;
    inputStream_ = null;
  }

  /**
   * Create a JSON reader for the specified input stream.
   * The actual reading is done with the read() or readData() methods.
   * <p>
   * Note that the client is responsible for closing the stream
   * after the read operation is complete.
   *
   * @param inputStream  Stream to read. Non-null.
   * @throws IllegalArgumentException  If inputStream is null.
   * @see #read
   * @see #readData
   */
  public JsonReader(InputStream inputStream)
  {
    file_ = null;
    inputStream_ = inputStream;
  }

  /**
   * Check the probability that the specified file is really a JSON
   * well log file.
   *
   * @param file     File to check. Non-null.
   * @param content  A number of bytes from the start of the file.
   *                 Null to classify on file name only.
   * @return Probability that the file is a JSON well log file. [0.0,1.0].
   * @throws IllegalArgumentException  If file is null.
   */
  public static double isJsonFile(File file, byte[] content)
  {
    if (file == null)
      throw new IllegalArgumentException("file cannot be null");

    if (file.isDirectory())
      return 0.0;

    if (!file.exists())
      return 0.0;

    boolean isFileNameMatching = file.getName().toLowerCase(Locale.US).endsWith(".json");
    boolean isContentMatching;

    return isFileNameMatching ? 0.9 : 0.3;
  }

  /**
   * Read curve data from the current location in the JSON file.
   *
   * @param jsonParser               The JSON parser. Non-null.
   * @param jsonFile                 The JSON file to populate with data. Non-null.
   * @param shouldReadBulkData       True if bulk data should be stored, false if not.
   * @param shouldCaptureStatistics  True to create statistics from the bulk data,
   *                                 false if not.
   * @param dataListener             Listener that will be notified when new data has
   *                                 been read. Null if not used.
   *
   */
  private static void readData(JsonParser jsonParser, JsonFile jsonFile,
                               boolean shouldReadBulkData,
                               boolean shouldCaptureStatistics,
                               JsonDataListener dataListener)
  {
    assert jsonParser != null : "jsonParser cannot be null";
    assert jsonFile != null : "jasonFile cannot be null";

    int curveNo = 0;
    int dimension = 0;
    int nDimensions = 1;

    int level = 0;

    while (jsonParser.hasNext()) {
      JsonParser.Event parseEvent = jsonParser.next();

      if (parseEvent == JsonParser.Event.START_ARRAY) {
        dimension = 0;
        level++;
      }

      else if (parseEvent == JsonParser.Event.END_ARRAY) {

        level--;

        //
        // If we get to level 0 we are all done. Validate the metadata index
        //
        if (level == 0)
          return;

        //
        // If at level 1 we have reached end of one row
        //
        if (level == 1) {
          curveNo = 0;
          dimension = 0;

          if (dataListener != null)
            dataListener.dataRead(jsonFile);
        }

        //
        // Otherwise we have reached the end of a n-dim curve
        //
        else {
          curveNo++;
          dimension = 0;
        }
      }

      else if (parseEvent == JsonParser.Event.START_OBJECT) {
        logger_.log(Level.SEVERE, "Unrecognized event in curve data: " + parseEvent + ". Aborting.");
        return;
      }

      else if (parseEvent == JsonParser.Event.END_OBJECT) {
        logger_.log(Level.SEVERE, "Unrecognized event in curve data: " + parseEvent + ". Aborting.");
        return;
      }

      else if (parseEvent == JsonParser.Event.KEY_NAME) {
        logger_.log(Level.SEVERE, "Unrecognized event in curve data: " + parseEvent + ". Aborting.");
        return;
      }

      else if (shouldReadBulkData || shouldCaptureStatistics) {
        Object value = null;
        if (parseEvent == JsonParser.Event.VALUE_NUMBER)
          value = jsonParser.getBigDecimal().doubleValue();
        else if (parseEvent == JsonParser.Event.VALUE_STRING)
          value = jsonParser.getString();
        else if (parseEvent == JsonParser.Event.VALUE_TRUE)
          value = Boolean.TRUE;
        else if (parseEvent == JsonParser.Event.VALUE_FALSE)
          value = Boolean.FALSE;

        JsonCurve curve = jsonFile.getCurves().get(curveNo);
        Class<?> valueType = curve.getValueType();
        nDimensions = curve.getNDimensions();
        Statistics statistics = curve.getStatistics();

        if (shouldCaptureStatistics)
          statistics.push(Util.getAsDouble(value));

        if (shouldReadBulkData)
          curve.addValue(dimension, Util.getAsType(value, valueType));

        // Move on to the next curve or dimension
        if (nDimensions == 1) {
          curveNo++;
          dimension = 0;
        }
        else {
          dimension++;
        }
      }
    }

    assert false : "Invalid state";
  }

  /**
   * Read a curve definition from the current location of the specified
   * JSON parser.
   *
   * @param jsonParser  The JSON parser. Non-null.
   * @return  The curve instance. Null if there is not adequate information to
   *          define a curve.
   */
  private static JsonCurve readCurveDefinition(JsonParser jsonParser)
  {
    assert jsonParser != null : "jsonParser cannot be null";

    String curveName = null;
    String description = null;
    String quantity = null;
    String unit = null;
    Class<?> valueType = null;
    int nDimensions = 1;

    while (jsonParser.hasNext()) {
      JsonParser.Event parseEvent = jsonParser.next();

      if (parseEvent == JsonParser.Event.END_OBJECT) {
        if (curveName == null) {
          logger_.log(Level.WARNING, "Curve name is nissing. Skip curve.");
          return null;
        }

        if (valueType == null) {
          logger_.log(Level.WARNING, "Curve value type is nissing. Skip curve.");
          return null;
        }

        JsonCurve curve = new JsonCurve(curveName, description,
                                        quantity, unit, valueType,
                                        nDimensions);
        return curve;
      }

      if (parseEvent == JsonParser.Event.KEY_NAME) {
        String key = jsonParser.getString();

        //
        // "name"
        //
        if (key.equals("name")) {
          parseEvent = jsonParser.next();
          curveName = jsonParser.getString();
        }

        //
        // "description"
        //
        else if (key.equals("description")) {
          parseEvent = jsonParser.next();
          description = parseEvent == JsonParser.Event.VALUE_STRING ? jsonParser.getString() : null;
        }

        //
        // "quantity"
        //
        else if (key.equals("quantity")) {
          parseEvent = jsonParser.next();
          quantity = parseEvent == JsonParser.Event.VALUE_STRING ? jsonParser.getString() : null;
        }

        //
        // "unit"
        //
        else if (key.equals("unit")) {
          parseEvent = jsonParser.next();
          unit = parseEvent == JsonParser.Event.VALUE_STRING ? jsonParser.getString() : null;
        }

        //
        // "valueType"
        //
        else if (key.equals("valueType")) {
          parseEvent = jsonParser.next();
          String valueTypeString = jsonParser.getString();
          JsonValueType jsonValueType = JsonValueType.get(valueTypeString);
          if (jsonValueType == null)
            logger_.log(Level.WARNING, "Unrecognized value type: " + valueTypeString + ". Using float instead.");
          valueType = jsonValueType != null ? jsonValueType.getValueType() : Double.class;
        }

        //
        // "dimensions"
        //
        else if (key.equals("dimensions")) {
          parseEvent = jsonParser.next();
          nDimensions = jsonParser.getInt();
        }
      }
    }

    return null;
  }

  /**
   * Read the curves information from the current location of the JSON file.
   *
   * @param jsonParser  The JSON parser. Non-null.
   * @param jsonFile    The instance to populate. Non-null.
   */
  private static void readCurveDefinitions(JsonParser jsonParser, JsonFile jsonFile)
  {
    assert jsonParser != null : "jsonParser cannot be null";
    assert jsonFile != null : "jsonFile cannot be null";

    while (jsonParser.hasNext()) {
      JsonParser.Event parseEvent = jsonParser.next();

      if (parseEvent == JsonParser.Event.END_ARRAY)
        return;

      if (parseEvent == JsonParser.Event.START_OBJECT) {
        JsonCurve curve = readCurveDefinition(jsonParser);
        if (curve != null)
          jsonFile.addCurve(curve);
      }
    }
  }

  /**
   * Read "log" object from the current position in the file
   * and return as a JsonFile instance.
   *
   * @param jsonParser          The parser. Non-null.
   * @param shouldReadBulkData  True if bulk data should be read, false
   *                            if only metadata should be read.
   * @param shouldCaptureStatistics  True if curve statistics should be
   *                            captures, false otherwise,
   * @param dataListener        Client data listener. Null if not used.
   * @return  The read instance. Never null.
   * @throws IOException  If the read operation fails for some reason.
   */
  private JsonFile readLog(JsonParser jsonParser,
                           boolean shouldReadBulkData,
                           boolean shouldCaptureStatistics,
                           JsonDataListener dataListener)
    throws IOException
  {
    JsonFile jsonFile = new JsonFile();

    while (jsonParser.hasNext()) {
      JsonParser.Event parseEvent = jsonParser.next();

      if (parseEvent == JsonParser.Event.END_OBJECT) {
        jsonFile.trimCurves();
        return jsonFile;
      }

      if (parseEvent == JsonParser.Event.KEY_NAME) {
        String key = jsonParser.getString();

        //
        // "metadata"
        //
        if (key.equals("metadata")) {
          JsonObjectBuilder objectBuilder = JsonUtil.readJsonObject(jsonParser);
          JsonObject metadata = objectBuilder.build();
          jsonFile.setMetadata(metadata);
        }

        //
        // "curves"
        //
        if (key.equals("curves")) {
          readCurveDefinitions(jsonParser, jsonFile);
        }

        //
        // "data"
        //
        if (key.equals("data")) {
          readData(jsonParser, jsonFile,
                   shouldReadBulkData,
                   shouldCaptureStatistics,
                   dataListener);
        }
      }
    }

    throw new IOException("Invalid JSON file: " + (file_ != null ? file_.toString() : inputStream_.toString()));
  }

  /**
   * Read data from a set of JSON files where the metadata has
   * already been read. This will preserve the existing JsonFile
   * structure in case a JSON file is read in two operations:
   *
   * <pre>
   *   // Read meta data
   *   List&lt;JsonFile&gt; jsonFiles = reader.read(false, ...);
   *
   *   // Read the curve data
   *   reader.readData(jsonFiles);
   * </pre>
   *
   * There is nothing to gain in performance with this approach
   * so in case the result is not cached, the following will
   * be equivalent:
   *
   * <pre>
   *   // Read metadata
   *   List&lt;JsonFile&gt; jsonFiles = reader.read(false, ...);
   *
   *   // Read all the data
   *   jsonFiles = reader.read(true, ...);
   * </pre>
   *
   * @param jsonFiles     The JSON files to populate. These must be the
   *                      exact same list as retrieved by calling the
   *                      #read(false,...) on the same DlisFileReader instance.
   *                      Otherwise the behavior is unpredictable.
   * @param shouldCaptureStatistics True to capture statistics per curve during read.
   *                      Statistics capture will reduce read performance slightly,
   *                      so set this to false if the statistics are not needed.
   * @param dataListener  Listener that will be notified when new data has been read.
   *                      Null if not used.
   * @throws IllegalArgumentException  If jsonFiles is null.
   * @throws IOException  If the read operation fails for some reason.
   */
  public void readData(List<JsonFile> jsonFiles, boolean shouldCaptureStatistics,
                       JsonDataListener dataListener)
    throws IOException
  {
    if (jsonFiles == null)
      throw new IllegalArgumentException("jsonFiles cannot be null");

    // Read everything into a new structure
    List<JsonFile> newJsonFiles = read(true, shouldCaptureStatistics, dataListener);

    // This is just a simple brain damage check. The client has all possible
    // ways to get into trouble if calling this method with an arbitrary argument.
    if (newJsonFiles.size() != jsonFiles.size())
      throw new IllegalArgumentException("The specified JSON files is incompatible with the original");

    // Move the frame data from the new to the existing
    for (int i = 0; i < jsonFiles.size(); i++) {
      JsonFile existingJsonFile = jsonFiles.get(i);
      JsonFile newJsonFile = newJsonFiles.get(i);

      existingJsonFile.setCurves(newJsonFile.getCurves());
    }
  }

  /**
   * Read all logs from the disk file of this reader.
   *
   * @param shouldReadBulkData  True if bulk data should be read, false
   *                            if only metadata should be read.
   * @param shouldCaptureStatistics  True if curve statistics should be
   *                            captures, false otherwise,
   * @param dataListener        Client data listener. Null if not used.
   * @return  List of logs contained in the file. Never null.
   * @throws IOException  If the read operation fails for some reason.
   */
  public List<JsonFile> read(boolean shouldReadBulkData,
                             boolean shouldCaptureStatistics,
                             JsonDataListener dataListener)
    throws IOException
  {
    List<JsonFile> jsonFiles = new ArrayList<>();

    InputStream inputStream = inputStream_ != null ? inputStream_ : new FileInputStream(file_);
    JsonParser jsonParser = Json.createParser(inputStream);

    while (jsonParser.hasNext()) {
      JsonParser.Event parseEvent = jsonParser.next();

      if (parseEvent == JsonParser.Event.KEY_NAME) {
        String key = jsonParser.getString();

        //
        // Log
        //
        if (key.equals("log")) {
          JsonFile jsonFile = readLog(jsonParser,
                                      shouldReadBulkData,
                                      shouldCaptureStatistics,
                                      dataListener);
          jsonFiles.add(jsonFile);
        }
      }
    }

    jsonParser.close();

    // We only close in the file input case.
    // Otherwise the client manage the stream.
    if (file_ != null)
      inputStream.close();

    return jsonFiles;
  }
}
