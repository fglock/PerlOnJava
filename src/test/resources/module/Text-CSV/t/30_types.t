#!/usr/bin/perl

use strict;
$^W = 1;

use Test::More tests => 25;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    use_ok "Text::CSV", ();
    plan skip_all => "Cannot load Text::CSV" if $@;
    }

$| = 1;

my $csv = Text::CSV->new ({
    types => [
	Text::CSV::IV (),
	Text::CSV::PV (),
	Text::CSV::NV (),
	],
    });

ok ($csv,					"CSV_XS->new ()");

is (@{$csv->{types}}, 3,			"->{types} as hash");
is ($csv->{types}[0], Text::CSV::IV (),	"type IV");
is ($csv->{types}[1], Text::CSV::PV (),	"type PV");
is ($csv->{types}[2], Text::CSV::NV (),	"type NV");

is (ref ($csv->types), "ARRAY",			"->types () as method");
is ($csv->types ()->[0], Text::CSV::IV (),	"type IV");
is ($csv->types ()->[1], Text::CSV::PV (),	"type PV");
is ($csv->types ()->[2], Text::CSV::NV (),	"type NV");

is (length $csv->{_types}, 3,			"->{_types}");
my $inp = join "", map { chr $_ }
    Text::CSV::IV (), Text::CSV::PV (), Text::CSV::NV ();
# should be "\001\000\002"
is ($csv->{_types}, $inp,			"IV PV NV");

ok ($csv->parse ("2.55,CSFDATVM01,3.75"),	"parse ()");
my @fields = $csv->fields ();
is ($fields[0], "2",				"Field 1");
is ($fields[1], "CSFDATVM01",			"Field 2");
is ($fields[2], "3.75",				"Field 3");

ok ($csv->combine ("", "", "1.00"),		"combine ()");
is ($csv->string, ',,1.00',			"string");

my $warning;
$SIG{__WARN__} = sub { $warning = shift };

ok ($csv->parse ($csv->string ()),		"parse (combine ())");
like ($warning, qr/numeric/,			"numeric warning");

@fields = $csv->fields ();
is ($fields[0], "0",				"Field 1");
is ($fields[1], "",				"Field 2");
is ($fields[2], "1",				"Field 3");

is ($csv->types (0), undef,			"delete types");
is ($csv->types,     undef,			"types gone");
