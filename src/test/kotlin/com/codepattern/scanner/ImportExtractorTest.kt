package com.codepattern.scanner

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportExtractorTest {

    // ---- Java ----

    @Test
    fun `extracts Java import`() {
        assertEquals("com.example.Foo", ImportExtractor.extractImportFromLine("import com.example.Foo;", "java"))
    }

    @Test
    fun `extracts Java static import`() {
        assertEquals("com.example.Foo.bar", ImportExtractor.extractImportFromLine("import static com.example.Foo.bar;", "java"))
    }

    @Test
    fun `ignores non-import Java lines`() {
        assertNull(ImportExtractor.extractImportFromLine("public class Foo {", "java"))
    }

    // ---- Kotlin ----

    @Test
    fun `extracts Kotlin import`() {
        assertEquals("com.example.Foo", ImportExtractor.extractImportFromLine("import com.example.Foo", "kotlin"))
    }

    // ---- Python ----

    @Test
    fun `extracts Python from-import`() {
        assertEquals("os.path", ImportExtractor.extractImportFromLine("from os.path import join", "python"))
    }

    @Test
    fun `extracts Python import`() {
        assertEquals("json", ImportExtractor.extractImportFromLine("import json", "python"))
    }

    // ---- TypeScript / JavaScript ----

    @Test
    fun `extracts TS import from`() {
        assertEquals("./services/auth", ImportExtractor.extractImportFromLine("import { AuthService } from './services/auth';", "typescript"))
    }

    @Test
    fun `extracts JS require`() {
        assertEquals("express", ImportExtractor.extractImportFromLine("const app = require('express')", "javascript"))
    }

    // ---- Go ----

    @Test
    fun `extracts Go import`() {
        assertEquals("fmt", ImportExtractor.extractImportFromLine("\"fmt\"", "go"))
    }

    // ---- C# ----

    @Test
    fun `extracts CSharp using`() {
        assertEquals("System.Collections.Generic", ImportExtractor.extractImportFromLine("using System.Collections.Generic;", "csharp"))
    }

    @Test
    fun `ignores CSharp using statement`() {
        assertNull(ImportExtractor.extractImportFromLine("using (var stream = new FileStream())", "csharp"))
    }

    @Test
    fun `ignores CSharp using var`() {
        assertNull(ImportExtractor.extractImportFromLine("using var stream = new FileStream();", "csharp"))
    }

    // ---- Rust ----

    @Test
    fun `extracts Rust use`() {
        assertEquals("std::collections::HashMap", ImportExtractor.extractImportFromLine("use std::collections::HashMap;", "rust"))
    }

    // ---- PHP ----

    @Test
    fun `extracts PHP use with alias`() {
        assertEquals("App\\Models\\User", ImportExtractor.extractImportFromLine("use App\\Models\\User as UserModel;", "php"))
    }

    // ---- Ruby ----

    @Test
    fun `extracts Ruby require`() {
        assertEquals("json", ImportExtractor.extractImportFromLine("require 'json'", "ruby"))
    }

    @Test
    fun `extracts Ruby require_relative`() {
        assertEquals("./models/user", ImportExtractor.extractImportFromLine("require_relative './models/user'", "ruby"))
    }

    // ---- Swift ----

    @Test
    fun `extracts Swift import`() {
        assertEquals("Foundation", ImportExtractor.extractImportFromLine("import Foundation", "swift"))
    }

    // ---- Dart ----

    @Test
    fun `extracts Dart import`() {
        assertEquals("package:flutter/material.dart", ImportExtractor.extractImportFromLine("import 'package:flutter/material.dart';", "dart"))
    }

    // ---- Bulk extraction ----

    @Test
    fun `extractImports returns all imports from lines`() {
        val lines = listOf(
            "package com.example;",
            "",
            "import com.example.Foo;",
            "import com.example.Bar;",
            "",
            "public class Baz {"
        )
        val imports = ImportExtractor.extractImports(lines, "java")
        assertEquals(listOf("com.example.Foo", "com.example.Bar"), imports)
    }

    @Test
    fun `extractImportLineNumbers maps imports to correct line numbers`() {
        val lines = listOf(
            "package com.example;",
            "",
            "import com.example.Foo;",
            "import com.example.Bar;",
        )
        val result = ImportExtractor.extractImportLineNumbers(lines, "java")
        assertEquals(3, result["com.example.Foo"])
        assertEquals(4, result["com.example.Bar"])
    }

    // ---- Direct instantiation detection ----

    @Test
    fun `detects Java new keyword instantiation`() {
        val lines = listOf(
            "Foo foo = new FooService();",
            "Bar bar = new BarRepository();"
        )
        val result = ImportExtractor.extractDirectInstantiations(lines, "java")
        assertTrue("FooService" in result)
        assertTrue("BarRepository" in result)
    }

    @Test
    fun `detects TypeScript new keyword instantiation`() {
        val lines = listOf("const svc = new UserService();")
        val result = ImportExtractor.extractDirectInstantiations(lines, "typescript")
        assertTrue("UserService" in result)
    }

    @Test
    fun `unknown language returns no imports`() {
        assertNull(ImportExtractor.extractImportFromLine("whatever", "haskell"))
    }
}
