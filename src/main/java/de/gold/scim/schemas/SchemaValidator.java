package de.gold.scim.schemas;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.gold.scim.constants.AttributeNames;
import de.gold.scim.constants.enums.Mutability;
import de.gold.scim.constants.enums.ReferenceTypes;
import de.gold.scim.constants.enums.Returned;
import de.gold.scim.constants.enums.Type;
import de.gold.scim.constants.enums.Uniqueness;
import de.gold.scim.exceptions.DocumentValidationException;
import de.gold.scim.exceptions.IncompatibleAttributeException;
import de.gold.scim.exceptions.InternalServerErrorException;
import de.gold.scim.exceptions.InvalidDateTimeRepresentationException;
import de.gold.scim.utils.HttpStatus;
import de.gold.scim.utils.JsonHelper;
import de.gold.scim.utils.TimeUtils;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;


/**
 * author Pascal Knueppel <br>
 * created at: 28.09.2019 - 18:43 <br>
 * <br>
 * This class is used to validate a SCIM json document against its SCIM meta schema. the meta schema is the
 * definition of the document that tells us how an attribute has to be built if it is required, multivalued,
 * complex etc. and will reduce the validated schema to those attribute that have been defined within the
 * schema. So all other additional attributes will be removed if validated if not they are not members of the
 * schema definition.
 */
@Slf4j
public final class SchemaValidator
{

  /**
   * this list contains the attributes that are valid parts of any document.
   */
  private static final List<String> COMMON_DOCUMENT_ATTRIBUTES = Arrays.asList(AttributeNames.SCHEMAS,
                                                                               AttributeNames.META);

  /**
   * this is the final validated document that has also been reduced to the valid attributes defined in the meta
   * schema. So all attributes unknown to the meta schema will be removed.
   */
  @Getter(AccessLevel.PRIVATE)
  private ObjectNode validatedDocument;

  /**
   * tells us if the schema is validated as request or as response
   *
   * @see DirectionType
   */
  private DirectionType directionType;

  /**
   * tells us which request type the user has used. This is e.g. necessary for immutable types that are valid on
   * POST requests but invalid on PUT requests
   */
  private HttpMethod httpMethod;

  private SchemaValidator(JsonNode deepCopy, DirectionType directionType, HttpMethod httpMethod)
  {
    this.validatedDocument = (ObjectNode)deepCopy;
    this.directionType = directionType;
    this.httpMethod = httpMethod;
  }

  /**
   * This method will build an instance of schema validator will then validate the document with the given
   * schema and returns a new document that conforms to the json meta schema definition.<br>
   * the validation direction of this method is {@link DirectionType#RESPONSE}
   *
   * @param metaSchema the json meta schema definition of the document
   * @param document the document to validate
   * @return the validated document that might have been reduced of some attributes
   */
  public static JsonNode validateSchemaForResponse(JsonNode metaSchema, JsonNode document)
  {
    SchemaValidator schemaValidator = new SchemaValidator(document.deepCopy(), DirectionType.RESPONSE, null);
    schemaValidator.validateSchema(metaSchema, schemaValidator.getValidatedDocument());
    return schemaValidator.getValidatedDocument();
  }

  /**
   * This method will build an instance of schema validator will then validate the document with the given
   * schema and returns a new document that conforms to the json meta schema definition <br>
   * the validation direction of this method is {@link DirectionType#REQUEST}
   *
   * @param metaSchema the json meta schema definition of the document
   * @param document the document to validate
   * @param httpMethod tells us which request type the client has used. This is e.g. necessary for immutable
   *          types that are valid on POST requests but invalid on PUT requests
   * @return the validated document that might have been reduced of some attributes
   */
  public static JsonNode validateSchemaForRequest(JsonNode metaSchema, JsonNode document, HttpMethod httpMethod)
  {
    SchemaValidator schemaValidator = new SchemaValidator(document.deepCopy(), DirectionType.REQUEST, httpMethod);
    schemaValidator.validateSchema(metaSchema, schemaValidator.getValidatedDocument());
    return schemaValidator.getValidatedDocument();
  }

  /**
   * this method will validate the given document against the given meta schema and check if the document is
   * valid
   *
   * @param metaSchema the document description
   * @param document the document that should be built after the rules of the metaSchema
   * @throws DocumentValidationException if the schema validation failed
   */
  private void validateSchema(JsonNode metaSchema, JsonNode document) throws DocumentValidationException
  {
    log.trace("validating metaSchema vs document");
    checkDocumentAndMetaSchemaRelationship(metaSchema, document);
    JsonNode attributes = getAttributes(metaSchema);
    try
    {
      validateAttributes(attributes, document);
    }
    catch (IncompatibleAttributeException ex)
    {
      throw DocumentValidationException.builder()
                                       .status(directionType.getHttpStatus())
                                       .message(ex.getMessage())
                                       .cause(ex)
                                       .build();
    }
  }

  /**
   * this method will verify that the meta schema is the correct schema to validate the document. This is done
   * by comparing the "id"-attribute of the metaSchema with the "schemas"-attribute of the document
   *
   * @param metaSchema the meta schema that should be used to validate the document
   * @param document the document that should be validated
   */
  private void checkDocumentAndMetaSchemaRelationship(JsonNode metaSchema, JsonNode document)
  {
    final String idAttribute = AttributeNames.ID;
    final String metaSchemaWithouidMessage = "meta schema does not have an '" + idAttribute + "'-attribute";
    String metaSchemaId = JsonHelper.getSimpleAttribute(metaSchema, idAttribute)
                                    .orElseThrow(() -> getException(metaSchemaWithouidMessage, null));

    final String schemasAttribute = AttributeNames.SCHEMAS;
    final String documentNoSchemasMessage = "document does not have a '" + schemasAttribute + "'-attribute";
    List<String> documentSchemas = JsonHelper.getSimpleAttributeArray(document, schemasAttribute)
                                             .orElseThrow(() -> getException(documentNoSchemasMessage, null));
    if (!documentSchemas.contains(metaSchemaId))
    {
      final String errorMessage = "document can not be validated against schema with id '" + metaSchemaId
                                  + "' for id is missing in the '" + schemasAttribute + "'-list: " + documentSchemas;
      throw getException(errorMessage, null);
    }
    log.trace("meta schema with id {} does apply to document with schemas '{}'", metaSchemaId, documentSchemas);
  }

  /**
   * takes a list of meta attributes that will be checked against a simple json document
   *
   * @param metaAttributes the meta attribute array that defines all the attributes of the document
   * @param document the json document itself that should be validated
   */
  private void validateAttributes(JsonNode metaAttributes, JsonNode document)
  {
    List<String> attributeDefinitionList = new ArrayList<>();
    for ( JsonNode metaAttribute : metaAttributes )
    {
      AttributeDefinition metaAttributeDefinition = new AttributeDefinition(metaAttribute);
      attributeDefinitionList.add(metaAttributeDefinition.getName());
      log.trace("validating attribute from meta-schema: {}", metaAttributeDefinition.toString());

      checkValueIsRequired(metaAttributeDefinition, document);

      if (Type.COMPLEX.equals(metaAttributeDefinition.getType()))
      {
        if (metaAttributeDefinition.isMultiValued())
        {
          validateMultiValuedComplexAttribute(metaAttribute, metaAttributeDefinition, document);
        }
        else
        {
          validateComplexAttribute(metaAttribute, metaAttributeDefinition, document);
        }
      }
      else
      {
        JsonNode jsonValueNode = document.get(metaAttributeDefinition.getName());
        if (metaAttributeDefinition.isMultiValued())
        {
          checkMultiValuedSimpleAttribute(metaAttributeDefinition, jsonValueNode);
        }
        else
        {
          isValueTypeValid(metaAttributeDefinition, jsonValueNode);
        }
      }
    }
    removeUnknownAttributes(attributeDefinitionList, document);
  }

  /**
   * this method will remove all attributes from the given object that are not defined within the meta schema
   *
   * @param metaAttributeDefinitionList a list of all meta attribute names that are allowed for the document
   * @param document the document that should have unknown attributes removed
   */
  private void removeUnknownAttributes(List<String> metaAttributeDefinitionList, JsonNode document)
  {
    metaAttributeDefinitionList.addAll(COMMON_DOCUMENT_ATTRIBUTES);
    ((ObjectNode)document).retain(metaAttributeDefinitionList);
  }

  /**
   * checks if the attribute of the meta schema requires the current attribute of the json document
   *
   * @param metaAttributeDefinition the meta attribute definition
   * @param attribute the attribute that is described by the meta attribute definition
   */
  private void checkValueIsRequired(AttributeDefinition metaAttributeDefinition, JsonNode attribute)
  {
    if (metaAttributeDefinition.isRequired())
    {
      boolean isValueAbsent = (metaAttributeDefinition.isMultiValued()
                               && !JsonHelper.getArrayAttribute(attribute, metaAttributeDefinition.getName())
                                             .isPresent())
                              || (!metaAttributeDefinition.isMultiValued()
                                  && !JsonHelper.getSimpleAttribute(attribute, metaAttributeDefinition.getName())
                                                .isPresent());
      if (isValueAbsent)
      {
        final String errorMessage = "schema does not hold required attribute '" + metaAttributeDefinition.getName()
                                    + "'";
        throw getException(errorMessage, null);
      }
    }
  }

  /**
   * checks if a multi valued complex attribute does apply to its meta schema definition
   *
   * @param metaAttribute the multi valued complex type definition from the meta schema
   * @param metaAttributeDefinition a pre calculated attribute definition that conforms to {@code metaAttribute}
   * @param multiValuedComplexType the multi valued complex type of the document that should be checked
   */
  private void validateMultiValuedComplexAttribute(JsonNode metaAttribute,
                                                   AttributeDefinition metaAttributeDefinition,
                                                   JsonNode multiValuedComplexType)
  {
    final String attributeName = AttributeNames.SUB_ATTRIBUTES;
    final String errorMessage = "multiValued complex attribute did not define attribute '" + attributeName + "'";
    JsonNode metaSubAttributes = JsonHelper.getArrayAttribute(metaAttribute, attributeName)
                                           .orElseThrow(() -> DocumentValidationException.builder()
                                                                                         .message(errorMessage)
                                                                                         .status(directionType.getHttpStatus())
                                                                                         .build());
    JsonNode documentSubAttributeArray = multiValuedComplexType.get(metaAttributeDefinition.getName());
    if (null == documentSubAttributeArray)
    {
      log.trace("non required attribute '{}' not defined in document", metaAttributeDefinition.getName());
    }
    else
    {
      for ( JsonNode jsonNode : documentSubAttributeArray )
      {
        validateAttributes(metaSubAttributes, jsonNode);
      }
    }
  }

  /**
   * will validate that all values of the defined attribute are correctly set
   *
   * @param metaAttributeDefinition the attribute definition of the meta schema
   * @param jsonNode the value node that should conform to the meta schema attribute definition
   */
  private void checkMultiValuedSimpleAttribute(AttributeDefinition metaAttributeDefinition, JsonNode jsonNode)
  {
    if (jsonNode == null)
    {
      // node is not present in the document so everything's fine
      return;
    }
    for ( JsonNode node : jsonNode )
    {
      isValueTypeValid(metaAttributeDefinition, node);
    }
  }

  /**
   * validates a simple complex type against the given attribute definition
   *
   * @param metaAttribute the complex type definition from the meta schema
   * @param metaAttributeDefinition a pre calculated attribute definition that conforms to {@code metaAttribute}
   * @param complexAttribute the complex attribute that should be validated
   */
  private void validateComplexAttribute(JsonNode metaAttribute,
                                        AttributeDefinition metaAttributeDefinition,
                                        JsonNode complexAttribute)
  {
    final String attributeName = AttributeNames.SUB_ATTRIBUTES;
    final String errorMessage = "multiValued complex attribute did not define attribute '" + attributeName + "'";
    JsonNode subAttributes = JsonHelper.getArrayAttribute(metaAttribute, attributeName)
                                       .orElseThrow(() -> DocumentValidationException.builder()
                                                                                     .message(errorMessage)
                                                                                     .status(directionType.getHttpStatus())
                                                                                     .build());
    JsonNode complexNode = complexAttribute.get(metaAttributeDefinition.getName());
    if (complexNode == null)
    {
      // node is not present in the document so everything's fine
      return;
    }
    if (metaAttributeDefinition.isMultiValued())
    {
      validateAttributes(subAttributes, complexNode);
    }
    else
    {
      isValueTypeValid(metaAttributeDefinition, subAttributes.get(metaAttributeDefinition.getName()));
    }
  }

  /**
   * checks if the value of the document field fits the defined type of the schema
   *
   * @param attributeDefinition the definition of the document node
   * @param valueNode the node that is described by the definition
   */
  private void isValueTypeValid(AttributeDefinition attributeDefinition, JsonNode valueNode)
  {
    if (valueNode == null)
    {
      // node is not present in the document so everything's fine
      log.trace("attribute {} not present in document: required = {}",
                attributeDefinition.getName(),
                attributeDefinition.isRequired());
      return;
    }
    Type type = attributeDefinition.getType();
    switch (type)
    {
      case STRING:
        isNodeOfExpectedType(attributeDefinition, valueNode, JsonNode::isTextual);
        break;
      case BOOLEAN:
        isNodeOfExpectedType(attributeDefinition, valueNode, JsonNode::isBoolean);
        break;
      case INTEGER:
        isNodeOfExpectedType(attributeDefinition, valueNode, JsonNode::isInt);
        break;
      case DECIMAL:
        isNodeOfExpectedType(attributeDefinition, valueNode, JsonNode::isFloat);
        break;
      case DATE_TIME:
        isNodeOfExpectedType(attributeDefinition, valueNode, JsonNode::isTextual);
        validateDateTime(attributeDefinition, valueNode);
        break;
      case REFERENCE:
        isNodeOfExpectedType(attributeDefinition, valueNode, JsonNode::isTextual);
        validateReferenceType(attributeDefinition, valueNode);
        break;
      default:
        throw DocumentValidationException.builder()
                                         .message("value node '" + attributeDefinition.getName()
                                                  + "' is of unknown type. " + "Expected type is: " + type.getValue())
                                         .status(directionType.getHttpStatus())
                                         .build();
    }
    log.trace("validated attribute '{}' as '{}' type, value is: {}",
              attributeDefinition.getName(),
              type.getValue(),
              valueNode.toString());
    checkCanonicalValues(attributeDefinition, valueNode);
    checkReturnedValue(attributeDefinition, valueNode);
    checkMutabilityValue(attributeDefinition, valueNode);
  }

  /**
   * checks if the given node is of the expected type
   *
   * @param attributeDefinition the meta attribute definition
   * @param valueNode the current value node that should be checked
   * @param isOfType the check that will validate if the node has the expected type
   */
  private void isNodeOfExpectedType(AttributeDefinition attributeDefinition,
                                    JsonNode valueNode,
                                    Function<JsonNode, Boolean> isOfType)
  {
    Type type = attributeDefinition.getType();
    final String errorMessage = "value of field with name '" + attributeDefinition.getName() + "' is not of " + "type '"
                                + type.getValue() + "' but of type: "
                                + StringUtils.lowerCase(valueNode.getNodeType().toString());
    if (attributeDefinition.isMultiValued())
    {
      for ( JsonNode node : valueNode )
      {
        checkAttributeValidity(isOfType.apply(node), errorMessage);
      }
    }
    else
    {
      checkAttributeValidity(isOfType.apply(valueNode), errorMessage);
    }
  }

  /**
   * verifies that the given value node is of type "dateTime"
   *
   * @param attributeDefinition the meta attribute definition of the field
   * @param valueNode the current value that should be of type "dateTime"
   */
  private void validateDateTime(AttributeDefinition attributeDefinition, JsonNode valueNode)
  {
    if (attributeDefinition.isMultiValued())
    {
      for ( JsonNode jsonNode : valueNode )
      {
        parseDateTime(jsonNode.textValue());
      }
    }
    else
    {
      parseDateTime(valueNode.textValue());
    }
  }

  /**
   * will check that the given reference type does conform to one of the expected types
   *
   * @param attributeDefinition the meta attribute definition of the given type
   * @param valueNode the current value that should be validated
   */
  private void validateReferenceType(AttributeDefinition attributeDefinition, JsonNode valueNode)
  {
    if (attributeDefinition.isMultiValued())
    {
      for ( JsonNode jsonNode : valueNode )
      {
        validateValueNodeWithReferenceTypes(attributeDefinition, jsonNode);
      }
    }
    else
    {
      validateValueNodeWithReferenceTypes(attributeDefinition, valueNode);
    }
  }

  /**
   * validates a simple value node against the valid resource types defined in the meta schema
   *
   * @param attributeDefinition the meta attribute definition
   * @param valueNode the value node
   */
  private void validateValueNodeWithReferenceTypes(AttributeDefinition attributeDefinition, JsonNode valueNode)
  {
    boolean isValidReferenceType = false;
    for ( ReferenceTypes referenceType : attributeDefinition.getReferenceTypes() )
    {
      switch (referenceType)
      {
        case RESOURCE:
          // TODO check if the referenced resource type was already registered
          isValidReferenceType = true;
          break;
        case URI:
          isValidReferenceType = parseUri(valueNode.textValue());
          break;
        default:
          isValidReferenceType = true;
      }
      if (isValidReferenceType)
      {
        break;
      }
    }
    checkAttributeValidity(isValidReferenceType,
                           "given value is not a valid reference type: " + valueNode.textValue()
                                                 + ": was expected to be of one of the following types: "
                                                 + attributeDefinition.getReferenceTypes());
  }

  /**
   * will verify that the current value node does define one of the canonical values of the attribute definition
   * if some are defined
   *
   * @param attributeDefinition the attribute definition from the meta schema
   * @param valueNode the value that matches to this definition
   */
  private void checkCanonicalValues(AttributeDefinition attributeDefinition, JsonNode valueNode)
  {
    if (attributeDefinition.getCanonicalValues().isEmpty())
    {
      // all values are valid
      return;
    }
    final String value = valueNode.textValue();
    if (!attributeDefinition.getCanonicalValues().contains(value))
    {
      final String errorMessage = "attribute with name '" + attributeDefinition.getName()
                                  + "' does not have one of the " + "canonicalValues: '"
                                  + attributeDefinition.getCanonicalValues() + "' actual value is: '" + value + "'";
      throw getException(errorMessage, null);
    }
  }

  /**
   * tries to parse the given text into a URI
   */
  private boolean parseUri(String textValue)
  {
    try
    {
      new URI(textValue);
      return true;
    }
    catch (URISyntaxException ex)
    {
      log.debug(ex.getMessage());
      return false;
    }
  }

  /**
   * tries to parse the given text as a xsd:datetime representation as defined in RFC7643 chapter 2.3.5
   */
  private void parseDateTime(String textValue)
  {
    try
    {
      TimeUtils.parseDateTime(textValue);
    }
    catch (InvalidDateTimeRepresentationException ex)
    {
      throw DocumentValidationException.builder()
                                       .status(directionType.getHttpStatus())
                                       .message("given value is not a valid dateTime: " + textValue)
                                       .cause(ex)
                                       .build();
    }
  }

  /**
   * any meta schema must have an attributes array that holds the field descriptions for the document
   *
   * @param metaSchema the meta schema that must have attributes
   * @return the attributes node
   */
  private JsonNode getAttributes(JsonNode metaSchema)
  {
    final String attributeName = AttributeNames.ATTRIBUTES;
    final String errorMessage = "meta schema is missing attribute '" + attributeName + "'";
    return JsonHelper.getArrayAttribute(metaSchema, attributeName)
                     .orElseThrow(() -> InternalServerErrorException.builder()
                                                                    .status(directionType.getHttpStatus())
                                                                    .message(errorMessage)
                                                                    .build());
  }

  /**
   * will check the returned value of the schema validation. Only the existence of the node is checked not the
   * value behind it. The value may still be null even if the value must be present in the response
   *
   * @param metaAttributeDefinition the current meta attribute definition
   * @param valueNode the value of the node
   */
  private void checkReturnedValue(AttributeDefinition metaAttributeDefinition, JsonNode valueNode)
  {
    Returned returned = metaAttributeDefinition.getReturned();

    // if the current validation is based on a request the returned value must not be checked
    if (DirectionType.REQUEST.equals(this.directionType))
    {
      return;
    }

    switch (returned)
    {
      case NEVER:
        if (valueNode != null)
        {
          log.warn("removing attribute '{}' from response because returned value is '{}'",
                   metaAttributeDefinition.getName(),
                   returned);
          // TODO remove attribute
        }
        break;
      case ALWAYS:
        checkAttributeValidity(valueNode == null,
                               "attribute '" + metaAttributeDefinition.getName() + "' must be"
                                                  + " present in response. Returned value is: " + returned.getValue());
        break;
      default:
        // TODO must be coupled with requiredAttributes value from request and for until then this case will never
        // fail
        checkAttributeValidity(true, null);
    }
  }

  /**
   * will check on the mutability of a given attribute and will handle the attribute accordingly to its
   * mutability
   *
   * @param metaAttributeDefinition the meta attribute definition
   * @param valueNode the document value of the meta attribute definition
   */
  private void checkMutabilityValue(AttributeDefinition metaAttributeDefinition, JsonNode valueNode)
  {
    if (DirectionType.REQUEST.equals(directionType))
    {
      checkMutabilityForRequest(metaAttributeDefinition, valueNode);
    }
    else
    {
      checkMutabilityForResponse(metaAttributeDefinition, valueNode);
    }
  }

  /**
   * will validate the mutability value of the attribute for a response based validation
   *
   * @param metaAttributeDefinition the attribute definition
   * @param valueNode the value node to check
   */
  private void checkMutabilityForResponse(AttributeDefinition metaAttributeDefinition, JsonNode valueNode)
  {
    Mutability mutability = metaAttributeDefinition.getMutability();
    switch (mutability)
    {
      case WRITE_ONLY:
        if (valueNode != null)
        {
          // TODO remove attribute from response
          log.warn("removing attribute '{}' because of its mutability of '{}'",
                   metaAttributeDefinition.getName(),
                   mutability.getValue());
        }
        break;
      default:
        log.trace("attribute '{}' has no conflicts with mutability value '{}' on {} validation",
                  metaAttributeDefinition.getName(),
                  mutability.getValue(),
                  DirectionType.RESPONSE);
    }
  }

  /**
   * will validate the mutability value of the attribute for a request based validation
   *
   * @param metaAttributeDefinition the attribute definition
   * @param valueNode the value node to check
   */
  private void checkMutabilityForRequest(AttributeDefinition metaAttributeDefinition, JsonNode valueNode)
  {
    Mutability mutability = metaAttributeDefinition.getMutability();
    switch (mutability)
    {
      case READ_ONLY:
        log.warn("TODO attribute '{}' must be removed from request since it has a mutability of '{}'",
                 metaAttributeDefinition.getName(),
                 mutability.getValue());
        break;
      case IMMUTABLE:
        String errorMessage = "attribute '" + metaAttributeDefinition.getName() + "' is " + mutability.getValue()
                              + " and is therefore only allowed on " + HttpMethod.POST + " requests";
        checkAttributeValidity(!HttpMethod.POST.equals(httpMethod) && valueNode != null, errorMessage);
        break;
      default:
        log.trace("attribute '{}' has no conflicts with mutability value '{}' on {} validation",
                  metaAttributeDefinition.getName(),
                  mutability.getValue(),
                  DirectionType.REQUEST);
    }
  }

  /**
   * checks if the expression is valid and throws an exception if not
   *
   * @param aBoolean the value of the expression to be checked
   * @param errorMessage the error message to display if the expression is false
   */
  private void checkAttributeValidity(Boolean aBoolean, String errorMessage)
  {
    if (!aBoolean)
    {
      throw getException(errorMessage, null);
    }
  }

  /**
   * builds an exception
   *
   * @param errorMessage the error message of the exception
   * @param cause the cause of this exception, may be null
   * @return a document validation exception
   */
  private DocumentValidationException getException(String errorMessage, Exception cause)
  {
    return DocumentValidationException.builder()
                                      .status(directionType.getHttpStatus())
                                      .cause(cause)
                                      .message(errorMessage)
                                      .build();
  }

  /**
   * tells us which request type the user has used. This is e.g. necessary for immutable types that are valid on
   * POST requests but invalid on PUT requests
   */
  public enum HttpMethod
  {
    POST, PUT, PATCH
  }

  /**
   * the direction type is used for validation. It tells us how a schema should be validated because there are
   * some differences. The meta-attribute for example is a required attribute for the response and therefore has
   * a readOnly mutability. In order to validate those attribute correctly we need to know if we validate the
   * schema as a request or as a response
   */
  protected enum DirectionType
  {
    REQUEST(HttpStatus.SC_BAD_REQUEST), RESPONSE(HttpStatus.SC_INTERNAL_SERVER_ERROR);

    /**
     * should be interna l server error if the response validation fails and a bad request if the request
     * validation fails
     */
    @Getter(AccessLevel.PRIVATE)
    private int httpStatus;

    DirectionType(int httpStatus)
    {
      this.httpStatus = httpStatus;
    }
  }

  /**
   * holds the data of an attribute definition with the known values
   */
  @Data
  protected class AttributeDefinition
  {

    /**
     * the name of the attribute
     */
    private String name;

    private Type type;

    private String description;

    private Mutability mutability;

    private Returned returned;

    private Uniqueness uniqueness;

    private boolean multiValued;

    private boolean required;

    /**
     * if the value of the attribute is case insensitive or not
     */
    private boolean caseExact;

    /**
     * the canonical values do represent an list of valid values that may be entered into the field within the
     * document. If no canonical values are set any value is valid in the field
     */
    private List<String> canonicalValues;

    /**
     * a list of valid reference types that may be valid within a reference type field
     */
    private List<ReferenceTypes> referenceTypes;

    public AttributeDefinition(JsonNode jsonNode)
    {
      Function<String, String> errorMessageBuilder = attribute -> "could not find required attribute '" + attribute
                                                                  + "' in meta-schema";
      final String nameAttribute = AttributeNames.NAME;
      final String nameErrorMessage = errorMessageBuilder.apply(nameAttribute);
      this.name = JsonHelper.getSimpleAttribute(jsonNode, nameAttribute)
                            .orElseThrow(() -> getException(nameErrorMessage, null));
      final String typeAttribute = AttributeNames.TYPE;
      final String typeErrorMessage = errorMessageBuilder.apply(typeAttribute);
      this.type = Type.getByValue(JsonHelper.getSimpleAttribute(jsonNode, typeAttribute)
                                            .orElseThrow(() -> getException(typeErrorMessage, null)));
      final String descriptionAttribute = AttributeNames.TYPE;
      final String descriptionErrorMessage = errorMessageBuilder.apply(descriptionAttribute);
      this.description = JsonHelper.getSimpleAttribute(jsonNode, descriptionAttribute)
                                   .orElseThrow(() -> getException(descriptionErrorMessage, null));
      this.mutability = Mutability.getByValue(JsonHelper.getSimpleAttribute(jsonNode, AttributeNames.MUTABILITY)
                                                        .orElse(null));
      this.returned = Returned.getByValue(JsonHelper.getSimpleAttribute(jsonNode, AttributeNames.RETURNED)
                                                    .orElse(null));
      this.uniqueness = Uniqueness.getByValue(JsonHelper.getSimpleAttribute(jsonNode, AttributeNames.UNIQUENESS)
                                                        .orElse(Uniqueness.NONE.getValue()));
      this.multiValued = JsonHelper.getSimpleAttribute(jsonNode, AttributeNames.MULTI_VALUED, Boolean.class)
                                   .orElse(false);
      this.required = JsonHelper.getSimpleAttribute(jsonNode, AttributeNames.REQUIRED, Boolean.class).orElse(false);
      this.caseExact = JsonHelper.getSimpleAttribute(jsonNode, AttributeNames.CASE_EXACT, Boolean.class).orElse(false);
      this.canonicalValues = JsonHelper.getSimpleAttributeArray(jsonNode, AttributeNames.CANONICAL_VALUES)
                                       .orElse(Collections.emptyList());
      this.referenceTypes = JsonHelper.getSimpleAttributeArray(jsonNode, AttributeNames.REFERENCE_TYPES)
                                      .map(strings -> strings.stream()
                                                             .map(ReferenceTypes::getByValue)
                                                             .collect(Collectors.toList()))
                                      .orElse(Collections.singletonList(ReferenceTypes.EXTERNAL));
    }

    @Override
    public String toString()
    {
      return "AttributeDefinition{" + "\n\tname='" + name + '\'' + "\n\ttype=" + type + "\n\tdescription='"
             + description + '\'' + "\n\tmutability=" + mutability + "\n\treturned=" + returned + "\n\tuniqueness="
             + uniqueness + "\n\tmultiValued=" + multiValued + "\n\trequired=" + required + "\n\tcaseExact=" + caseExact
             + "\n\tcanonicalValues=" + canonicalValues + '}';
    }
  }
}