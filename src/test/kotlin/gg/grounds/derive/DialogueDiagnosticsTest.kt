package gg.grounds.derive

import gg.grounds.dialogue.core.validation.ValidationReport
import gg.grounds.domain.DeriveFailureScope
import gg.grounds.domain.DeriveProblem
import kotlin.test.*
import org.junit.jupiter.api.Test

class DialogueDiagnosticsTest {
    @Test
    fun mapsLibraryPathToPointerWithoutLosingCode() {
        val original =
            ValidationReport.Problem(
                "REFERENCE_UNKNOWN",
                ValidationReport.Severity.ERROR,
                "guide.json",
                "/nodes/greeting",
                "greeting",
                "Unknown target",
            )
        val actual = DialogueDiagnostics.fromLibrary(original, setOf("guide.json"))
        assertEquals("REFERENCE_UNKNOWN", actual.code)
        assertEquals("dialogues/guide.json", actual.file)
        assertEquals("/nodes/greeting", actual.pointer)
        assertEquals("greeting", actual.nodeId)
        assertEquals("ERROR", actual.severity)
    }

    @Test
    fun truncationNeverConvertsFailureToSuccess() {
        val rows =
            (1..1500).map {
                DeriveProblem(
                    DeriveFailureScope.CONTENT,
                    null,
                    "WARN_$it",
                    null,
                    "text",
                    severity = "WARNING",
                )
            } +
                DeriveProblem(
                    DeriveFailureScope.CONTENT,
                    null,
                    "ZZ_ERROR",
                    null,
                    "error",
                    severity = "ERROR",
                )
        val report = DialogueDiagnostics.bounded(rows)
        assertTrue(report.size <= 1000)
        assertTrue(report.any { it.code == "DIAGNOSTICS_TRUNCATED" && it.severity == "ERROR" })
        assertTrue(CanonicalJson.write(report).size <= 1048576)
    }

    @Test
    fun utf8BudgetBoundsEvenOneHugeDiagnostic() {
        val input =
            listOf(
                DeriveProblem(
                    DeriveFailureScope.CONTENT,
                    null,
                    "BAD",
                    null,
                    "🔧".repeat(300000),
                    severity = "ERROR",
                )
            )
        val report = DialogueDiagnostics.bounded(input)
        assertTrue(CanonicalJson.write(report).size <= 1048576)
        assertTrue(report.any { it.code == "DIAGNOSTICS_TRUNCATED" && it.severity == "ERROR" })
    }
}
