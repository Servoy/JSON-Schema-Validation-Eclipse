package sj.jsonschemavalidation.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sj.jsonschemavalidation.builder.JsonValidator.Problem;

/**
 * Tests for the pure validation core {@link JsonValidator}. These exercise the
 * actual JSON parsing and schema validation (via the networknt validator) and the
 * JSON-pointer to line-number mapping, without any running Eclipse workbench.
 */
public class JsonValidatorTest {

	private final JsonValidator validator = new JsonValidator();

	private static final String PRODUCTS_SCHEMA = "{\n"
			+ "  \"$schema\": \"http://json-schema.org/draft-07/schema#\",\n"
			+ "  \"type\": \"object\",\n"
			+ "  \"properties\": {\n"
			+ "    \"Products\": {\n"
			+ "      \"type\": \"array\",\n"
			+ "      \"items\": {\n"
			+ "        \"type\": \"object\",\n"
			+ "        \"properties\": { \"Name\": { \"type\": \"string\" } },\n"
			+ "        \"required\": [ \"Name\" ]\n"
			+ "      }\n"
			+ "    }\n"
			+ "  }\n"
			+ "}\n";

	@Test
	void wellFormedJsonWithoutSchemaHasNoProblems() {
		List<Problem> problems = validator.validate("{ \"a\": 1, \"b\": [1, 2, 3] }", null);
		assertTrue(problems.isEmpty(), () -> "expected no problems but got: " + problems);
	}

	@Test
	void validDocumentAgainstSchemaHasNoProblems() {
		String json = "{ \"Products\": [ { \"Name\": \"Book\" }, { \"Name\": \"Pen\" } ] }";
		List<Problem> problems = validator.validate(json, PRODUCTS_SCHEMA);
		assertTrue(problems.isEmpty(), () -> "expected valid but got: " + problems);
	}

	@Test
	void wrongTypeIsReportedAtTheRightPointer() {
		String json = "{\n"
				+ "  \"Products\": [\n"
				+ "    { \"Name\": \"ok\" },\n"
				+ "    { \"Name\": 123 }\n"
				+ "  ]\n"
				+ "}\n";
		List<Problem> problems = validator.validate(json, PRODUCTS_SCHEMA);

		assertEquals(1, problems.size(), () -> "problems: " + problems);
		Problem p = problems.get(0);
		assertEquals("/Products/1/Name", p.getPointer());
		// The offending "Name": 123 sits on line 4 of the document.
		assertEquals(4, p.getLineNumber(), () -> "problem: " + p);
		assertFalse(p.getMessage().isEmpty());
	}

	@Test
	void missingRequiredPropertyIsReported() {
		String json = "{ \"Products\": [ { } ] }";
		List<Problem> problems = validator.validate(json, PRODUCTS_SCHEMA);

		assertEquals(1, problems.size(), () -> "problems: " + problems);
		Problem p = problems.get(0);
		// networknt reports required-property violations on the containing object.
		assertEquals("/Products/0", p.getPointer());
		assertTrue(p.getMessage().toLowerCase().contains("name"),
				() -> "expected message to mention the missing 'Name': " + p.getMessage());
	}

	@Test
	void multipleViolationsAreAllReported() {
		String json = "{\n"
				+ "  \"Products\": [\n"
				+ "    { \"Name\": 1 },\n"
				+ "    { }\n"
				+ "  ]\n"
				+ "}\n";
		List<Problem> problems = validator.validate(json, PRODUCTS_SCHEMA);
		// one type error + one missing-required error
		assertEquals(2, problems.size(), () -> "problems: " + problems);
	}

	@Test
	void malformedJsonProducesASyntaxProblem() {
		// missing closing brace / trailing comma
		String json = "{ \"a\": 1, }";
		List<Problem> problems = validator.validate(json, PRODUCTS_SCHEMA);

		assertEquals(1, problems.size(), () -> "problems: " + problems);
		Problem p = problems.get(0);
		assertEquals("", p.getPointer(), "syntax errors have no instance pointer");
		assertTrue(p.getLineNumber() >= 1);
		assertFalse(p.getMessage().isEmpty());
	}

	@Test
	void malformedJsonIsReportedEvenWithoutSchema() {
		List<Problem> problems = validator.validate("not json at all", null);
		assertEquals(1, problems.size(), () -> "problems: " + problems);
		assertEquals("", problems.get(0).getPointer());
	}

	@Test
	void draftIsAutoDetectedFromSchema() {
		// A draft 2020-12 schema using "prefixItems" (unknown to draft-07) must be
		// honoured, proving the $schema-based version detection works.
		String schema = "{\n"
				+ "  \"$schema\": \"https://json-schema.org/draft/2020-12/schema\",\n"
				+ "  \"type\": \"array\",\n"
				+ "  \"prefixItems\": [ { \"type\": \"string\" }, { \"type\": \"number\" } ]\n"
				+ "}\n";
		// second element should be a number, but is a string -> one violation
		List<Problem> problems = validator.validate("[ \"a\", \"b\" ]", schema);
		assertEquals(1, problems.size(), () -> "problems: " + problems);
		assertEquals("/1", problems.get(0).getPointer());
	}
}
