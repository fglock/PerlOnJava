use strict;
use warnings;
use Test::More tests => 2;

BEGIN {
    package Issue1649::FirstLazyExporter;
    require Exporter;
    our @ISA = qw(Exporter);
    our @EXPORT = qw(exported_caller);

    sub import {
        my $self = shift;
        $self->export_to_level(1, $self, @_);
    }

    sub exported_caller { scalar caller }

    $INC{'Issue1649/FirstLazyExporter.pm'} = __FILE__;
}

{
    package Issue1649::FirstLazyExporter::Client;
    use Issue1649::FirstLazyExporter qw(exported_caller);

    our $observed = defined(&exported_caller)
        ? &exported_caller()
        : 'missing';
}

ok(defined(&Issue1649::FirstLazyExporter::Client::exported_caller),
    'export_to_level installs the symbol in the importing package');
is($Issue1649::FirstLazyExporter::Client::observed,
    'Issue1649::FirstLazyExporter::Client',
    'the exported sub sees the importing package as its caller');
