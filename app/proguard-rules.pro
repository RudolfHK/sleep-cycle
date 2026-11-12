# kotlinx.serialization ships its own consumer rules; keep backup DTO names stable.
-keep class de.sleeptracker.app.data.db.** { *; }
-keepattributes *Annotation*
