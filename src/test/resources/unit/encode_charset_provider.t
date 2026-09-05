use strict;
use warnings;
use Test::More;

use Encode ();
use Pod::Simple::TranscodeSmart ();

# JCodings contributes a Java CharsetProvider.  Its service descriptor must
# name the relocated provider class in the standalone PerlOnJava JAR.
my @encodings = Encode::encodings(':all');
ok(@encodings, 'enumerates available encodings');
ok(grep(/\A(?:windows-1252|cp1252)\z/i, @encodings),
   'enumeration includes the Windows-1252 charset');

ok(Pod::Simple::TranscodeSmart->encoding_is_available('cp1252'),
   'Pod::Simple can resolve a CP1252 transcoder encoding');

done_testing;
