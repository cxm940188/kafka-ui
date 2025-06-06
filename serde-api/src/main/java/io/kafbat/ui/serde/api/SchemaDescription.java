package io.kafbat.ui.serde.api;

import java.util.Map;

/**
 * Description of topic's key/value schema.
 */
public final class SchemaDescription {

  private final String schema;
  private final Map<String, Object> additionalProperties;

  /**
   * Constructor for {@code SchemaDescription}.
   * @param schema schema descriptions.
   *              If contains json-schema (preferred) UI will use it for validation and sample data generation.
   * @param additionalProperties additional properties about schema (may be rendered in UI in the future)
   */
  public SchemaDescription(String schema, Map<String, Object> additionalProperties) {
    this.schema = schema;
    this.additionalProperties = additionalProperties;
  }

  /**
   * Schema description text. Can be null.
   * @return schema description text. Preferably contains json-schema. Can be null.
   */
  public String getSchema() {
    return schema;
  }

  /**
   * Additional properties about schema.
   * @return additional properties about schema
   */
  public Map<String, Object> getAdditionalProperties() {
    return additionalProperties;
  }
}
