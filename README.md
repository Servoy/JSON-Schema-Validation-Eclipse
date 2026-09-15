# About
JSON Schema Validation is an Plug-In for the Eclipse IDE that validates JSON files
and shows error markers. Using the [networknt JSON Schema Validator](https://github.com/networknt/json-schema-validator),
it checks for syntactic and -- if a [schema](http://json-schema.org/latest/json-schema-core.html)
is present -- semantic errors in JSON files. Schema drafts v4, v6, v7, 2019-09 and
2020-12 are supported; the draft is auto-detected from the schema's `$schema` value
and defaults to draft-07 when none is declared.
It may be used together with the 
[Eclipse JSON Editor](http://sourceforge.net/projects/eclipsejsonedit/).

The Plug-In looks for Schemas in the same directory as the data file
having the same basename followed by `Schema.json` or `.schema.json`.
E. g. when a `BooksSchema.json` is present, it will be taken as schema for a file named `Books.json`.
If no such file exists, it looks for a file `schema.json` in the current directory. 

The terms of the Mozilla Public License Version 2.0 apply for distributing this software. See `LICENSE.txt`.


# Requirements
Requires Eclipse 2025-06 (4.36) or later and a Java 21 (or higher) runtime. 


# Usage
To activate JSON Validation, right click a project, and under 
`Configure` choose `Add/Remove Json Validation Nature`.


# Limitations
* Does not recognize sub-schemas.


# Building
The build is a standard [Tycho](https://github.com/eclipse-tycho/tycho) build. All
third-party dependencies are resolved automatically; nothing is committed to the
repository:

* the Eclipse platform comes from the plain Eclipse update site
  (`https://download.eclipse.org/releases/2025-06/`), and
* the JSON Schema validator and Jackson are pulled straight from Maven Central
  through the Maven target location in `sj.jsonschemavalidation.target`.


## Using Maven
Requires: Maven 3.9+ and a JDK 21+.

Run `mvn package` to generate the plug-in JAR (in directory `target`), suitable to
be placed into the `dropins`-folder of Eclipse for installation.


## Using Eclipse
Requires: Eclipse 2025-06 (4.36) or later with the Plug-In Development Tools (PDE)
and m2e installed.

Open `sj.jsonschemavalidation.target` and click *Set as Active Target Platform*
(m2e resolves the Maven dependencies for you). The plug-in can then be built, run
and exported as `Deployable Plug-Ins and Fragments` from within Eclipse. 