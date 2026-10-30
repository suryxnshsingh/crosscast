# Crosscast keeps no reflection-based code; R8's defaults plus the libraries' own rules suffice.
# Keep line numbers so crash stack traces from release builds stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
