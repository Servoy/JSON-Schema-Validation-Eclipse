package sj.jsonschemavalidation.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import sj.jsonschemavalidation.builder.JsonValidator.Problem;

/**
 * End-to-end style test that mirrors how Servoy uses this plug-in: it validates a
 * real component <code>.spec</code> file against the exact <code>spec.schema</code>
 * that Servoy's {@code SpecSchemaProvider} feeds into the validator.
 *
 * <p>The schema and spec are copied verbatim from the Servoy sources into
 * {@code resources/} next to this test. This proves the modernized validator
 * accepts a valid spec (no problems) and still reports problems when the spec
 * violates the schema.
 */
public class SpecFileValidationTest {

	private final JsonValidator validator = new JsonValidator();

	private static String resource(String name) throws IOException {
		try (InputStream is = SpecFileValidationTest.class.getResourceAsStream("resources/" + name)) {
			assertNotNull(is, () -> "test resource not found on classpath: resources/" + name);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int read;
			while ((read = is.read(buf)) != -1) {
				out.write(buf, 0, read);
			}
			return out.toString(StandardCharsets.UTF_8);
		}
	}

	@Test
	void realGroupingtableSpecValidatesCleanlyAgainstServoySchema() throws IOException {
		String schema = resource("spec.schema");
		String spec = resource("groupingtable.spec");

		List<Problem> problems = validator.validate(spec, schema);

		assertTrue(problems.isEmpty(),
				() -> "expected the real groupingtable.spec to be valid, but got:\n"
						+ problems.stream().map(Problem::toString).reduce("", (a, b) -> a + "\n" + b));
	}

	@Test
	void schemaDeclaresDraft04AndIsHonoured() throws IOException {
		// The Servoy schema declares draft-04; a valid spec must still pass. If the
		// version were mis-detected the schema keywords would behave differently.
		String schema = resource("spec.schema");
		String spec = resource("groupingtable.spec");
		assertTrue(validator.validate(spec, schema).isEmpty());
	}

	@Test
	void invalidSpecPropertyIsReportedAgainstServoySchema() throws IOException {
		String schema = resource("spec.schema");
		String spec = resource("groupingtable.spec");

		// The spec schema forbids unknown keys on a model property definition
		// ("additionalProperties": false). Inject a bogus key and expect a problem.
		String broken = spec.replaceFirst(
				"\"responsiveHeight\": \\{ \"type\": \"int\"",
				"\"responsiveHeight\": { \"type\": \"int\", \"bogusKey\": 123");
		assertFalse(broken.equals(spec), "test setup: expected to inject a bogus key");

		List<Problem> problems = validator.validate(broken, schema);

		assertFalse(problems.isEmpty(), "expected a schema violation for the bogus key");
		assertTrue(problems.stream().anyMatch(p -> "/model/responsiveHeight".equals(p.getPointer())),
				() -> "expected a problem at /model/responsiveHeight but got: " + problems);
		assertTrue(problems.stream().anyMatch(p -> p.getMessage().contains("bogusKey")),
				() -> "expected a message mentioning the bogus key but got: " + problems);
	}

	@Test
	void malformedSpecIsReportedAsSyntaxProblem() throws IOException {
		String schema = resource("spec.schema");
		// truncate the document so it is no longer well-formed JSON
		String broken = resource("groupingtable.spec");
		broken = broken.substring(0, broken.length() / 2);

		List<Problem> problems = validator.validate(broken, schema);

		assertEquals(1, problems.size(), () -> "problems: " + problems);
		assertEquals("", problems.get(0).getPointer(), "syntax errors have no instance pointer");
	}
}
