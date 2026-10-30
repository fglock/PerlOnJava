use strict;
use warnings;
use Test::More;
use B qw(svref_2object);
use Sub::Exporter;

{
    package ImportViaSubExporter;
    use Sub::Exporter -setup => {
        exports => [qw(foo)],
    };

    sub foo { 'FOO' }
}

my $importer = ImportViaSubExporter->can('import');
is(svref_2object($importer)->START->stashpv, 'Sub::Exporter',
    'B::CV START identifies the package that created a Sub::Exporter importer');

$importer->('ImportViaSubExporter', foo => { -as => 'bar' });
is(bar(), 'FOO', 'detected Sub::Exporter importer supports renamed imports');

done_testing;
