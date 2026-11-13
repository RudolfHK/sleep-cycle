package de.sleeptracker.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImporterTest {

    private val header = "Went to bed;Woke up;Sleep Quality;Time in bed (seconds);Time asleep (seconds);Asleep after (seconds);" +
        "Regularity;Did snore;Snore time (seconds);Coughing (per hour);Steps;City;Breathing disruptions (per hour);" +
        "Ambient noise (dB);Ambient light (lux);Alertness score;Alertness reaction time (seconds);Alertness accuracy;" +
        "Movements per hour;Wake up window start;Wake up window stop"

    @Test
    fun currentCsvFormatSemicolon() {
        val csv = "﻿$header\n" +
            "2026-06-07 01:20:42;2026-06-07 08:43:53;76%;26590;22070;531;—;false;0;0;0;;0;26.681635;0;88%;707;100%;98.745224;2026-06-07 08:37:00;2026-06-07 09:00:00\n" +
            "2026-06-08 00:46:00;2026-06-08 07:15:44;67%;23384;19954;1480;83%;true;220;0,5;0;\"Berlin; DE\";1.54;26.69;12.5;;;;70.79;2026-06-08 07:35:07;2026-06-08 08:00:00\n"
        val r = SleepCycleParser.parse(csv.toByteArray())
        assertEquals(ParseResult.Format.SLEEP_CYCLE, r.format)
        assertEquals(2, r.nights.size)
        val a = r.nights[0]
        assertEquals(LocalDateTime.of(2026, 6, 7, 1, 20, 42), a.start)
        assertEquals(76, a.qualityPercent)
        assertEquals(26590L, a.timeInBedSeconds)
        assertEquals(22070L, a.timeAsleepSeconds)
        assertEquals(531L, a.asleepAfterSeconds)
        assertNull(a.regularityPercent)
        assertEquals(false, a.didSnore)
        assertEquals(26.681635f, a.noiseDb!!, 1e-4f)
        assertEquals(LocalDateTime.of(2026, 6, 7, 8, 37), a.windowStart)
        val b = r.nights[1]
        assertEquals(83, b.regularityPercent)
        assertEquals(220L, b.snoreSeconds)
        assertEquals(0.5f, b.coughsPerHour!!, 1e-6f)
        assertEquals(12.5f, b.lightLux!!, 1e-6f)
    }

    @Test
    fun commaDelimitedWithQuotes() {
        val csv = header.replace(';', ',') + "\r\n" +
            "\"2026-06-09 01:26:10\",\"2026-06-09 07:41:04\",\"80%\",\"22493\",\"15745\",\"1199\",\"87%\",\"false\",\"0\",\"0\",\"0\",\"\",\"0\",\"26.8\",\"0\",,,,\"53.5\",\"2026-06-09 07:37:16\",\"2026-06-09 08:00:00\"\r\n"
        val r = SleepCycleParser.parse(csv.toByteArray())
        assertEquals(1, r.nights.size)
        assertEquals(87, r.nights[0].regularityPercent)
        assertEquals(53.5f, r.nights[0].movementsPerHour!!, 1e-6f)
    }

    @Test
    fun legacyFormat() {
        val csv = "Start;End;Sleep quality;Time in bed;Wake up;Sleep Notes;Heart rate;Activity (steps)\n" +
            "2014-01-01 23:30:00;2014-01-02 07:00:00;72%;7:30;:);Drank coffee:Stressful day;58;8000\n" +
            "2014-01-02 23:00:00;2014-01-02 22:00:00;50%;0:10;;;;\n"
        val r = SleepCycleParser.parse(csv.toByteArray())
        assertEquals(ParseResult.Format.SLEEP_CYCLE_LEGACY, r.format)
        assertEquals(1, r.nights.size)
        assertEquals(1, r.problems.size)
        assertEquals(RowProblem.Reason.END_BEFORE_START, r.problems[0].reason)
        val n = r.nights[0]
        assertEquals(7 * 3600L + 30 * 60, n.timeInBedSeconds)
        assertNull(n.timeAsleepSeconds)
        assertEquals(4, n.mood)
        assertEquals(listOf("Drank coffee", "Stressful day"), n.notes)
        assertEquals(58f, n.heartRateBpm!!, 1e-6f)
        assertEquals(8000, n.steps)
    }

    @Test
    fun unknownFileIsRecognised() {
        val r = SleepCycleParser.parse("a,b,c\n1,2,3\n".toByteArray())
        assertEquals(ParseResult.Format.UNKNOWN, r.format)
        assertTrue(r.nights.isEmpty())
    }

    @Test
    fun valueParsing() {
        assertEquals(76, SleepCycleParser.percent("0.76"))
        assertEquals(76, SleepCycleParser.percent("76%"))
        assertEquals(76, SleepCycleParser.percent("76"))
        assertEquals(26.68, SleepCycleParser.number("26,68")!!, 1e-9)
        assertEquals(1234.5, SleepCycleParser.number("1.234,5")!!, 1e-9)
        assertEquals(LocalDateTime.of(2026, 6, 7, 1, 20, 42), SleepCycleParser.parseDateTime("46180.05604166666"))
        assertEquals(LocalDateTime.of(2026, 6, 7, 1, 20), SleepCycleParser.parseDateTime("07.06.2026 01:20"))
        // Two-digit year, disambiguated by the wake-up time.
        assertEquals(LocalDateTime.of(2026, 6, 7, 8, 37), SleepCycleParser.parseDateTime("26-06-07 08:37:00", near = LocalDateTime.of(2026, 6, 7, 8, 43)))
        assertEquals("—", TableReader.cleanText("â_x0080__x0094_"))
        assertEquals("Went to bed", TableReader.cleanText("ï»¿Went to bed"))
        assertEquals(27, TableReader.columnIndex("AB12"))
    }

    /** Builds an .xlsx like the one produced when a Sleep Cycle CSV is re-saved by a spreadsheet app. */
    private fun xlsx(): ByteArray {
        val shared = listOf("ï»¿Went to bed", "Woke up", "Sleep Quality", "Time in bed (seconds)", "Time asleep (seconds)",
            "Asleep after (seconds)", "Regularity", "Did snore", "Snore time (seconds)", "Ambient noise (dB)",
            "Ambient light (lux)", "Movements per hour", "Wake up window start", "Wake up window stop",
            "â_x0080__x0094_", "26-06-07 08:37:00", "26-06-07 09:00:00")
        fun s(ref: String, i: Int) = "<c r=\"$ref\" t=\"s\"><v>$i</v></c>"
        fun n(ref: String, v: String) = "<c r=\"$ref\"><v>$v</v></c>"
        val headerRow = "<row r=\"1\">" + (0..13).joinToString("") { s("${'A' + it}1", it) } + "</row>"
        val row2 = "<row r=\"2\">" + n("A2", "46180.05604166666") + n("B2", "46180.36380787037") + n("C2", "0.76") +
            n("D2", "26590.0") + n("E2", "22070.0") + n("F2", "531.0") + s("G2", 14) + "<c r=\"H2\" t=\"b\"><v>1</v></c>" +
            n("I2", "220.0") + n("J2", "26.681635") + /* K2 (light) missing */ n("L2", "98.7") + s("M2", 15) + s("N2", 16) + "</row>"
        val row3 = "<row r=\"3\">" + "<c r=\"A3\" t=\"inlineStr\"><is><t>2026-06-08 00:46:00</t></is></c>" +
            "<c r=\"B3\" t=\"inlineStr\"><is><t>2026-06-08 07:15:44</t></is></c>" + n("C3", "0.67") + n("D3", "23384") + "</row>"
        val sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>$headerRow$row2$row3</sheetData></worksheet>"
        val sst = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            shared.joinToString("") { "<si><t>${it.replace("&", "&amp;")}</t></si>" } + "</sst>"
        val workbook = "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId3\"/></sheets></workbook>"
        val rels = "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId3\" Type=\"worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>"
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, content) in listOf("xl/workbook.xml" to workbook, "xl/_rels/workbook.xml.rels" to rels, "xl/sharedStrings.xml" to sst, "xl/worksheets/sheet1.xml" to sheet)) {
                z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray()); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun xlsxExport() {
        val r = SleepCycleParser.parse(xlsx())
        assertEquals(ParseResult.Format.SLEEP_CYCLE, r.format)
        assertEquals(2, r.nights.size)
        val a = r.nights[0]
        assertEquals(LocalDateTime.of(2026, 6, 7, 1, 20, 42), a.start)
        assertEquals(LocalDateTime.of(2026, 6, 7, 8, 43, 53), a.end)
        assertEquals(76, a.qualityPercent)
        assertNull(a.regularityPercent)
        assertEquals(true, a.didSnore)
        assertNull(a.lightLux)
        assertEquals(LocalDateTime.of(2026, 6, 7, 8, 37), a.windowStart)
        assertEquals(LocalDateTime.of(2026, 6, 7, 9, 0), a.windowStop)
        assertEquals(LocalDateTime.of(2026, 6, 8, 0, 46), r.nights[1].start)
        assertNull(r.nights[1].timeAsleepSeconds)
    }
}
