use strict;
use warnings;

use Exporter ();

{
    package ExporterHeavyCallerFixture;
    our @ISA = ('Exporter');
    our @EXPORT_OK = ('exported_value');

    sub exported_value { return 42 }
}

ExporterHeavyCallerFixture->import('exported_value');

print exported_value() == 42 ? "ok 1 - Exporter dispatches through heavy export\n"
                              : "not ok 1 - Exporter dispatches through heavy export\n";
print "1..1\n";
