#!/usr/bin/perl

use strict;
$^W = 1;

 use Test::More tests => 111;
#use Test::More "no_plan";

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    require_ok "Text::CSV";
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";
    }

$| = 1;

my $csv;
my $tfn = "_79test.csv"; END { -f $tfn and unlink $tfn; }

# These tests are for the constructor
{   my $warn;
    local $SIG{__WARN__} = sub { $warn = shift; };
    ok ($csv = Text::CSV->new ({ callbacks => undef	}),	"new");
    is ($warn, undef,			"no warn for undef");
    is ($csv->callbacks, $warn = undef,	"no callbacks for undef");
    ok ($csv = Text::CSV->new ({ callbacks => 0	}),	"new");
    like ($warn, qr{: ignored\n},	"warn for 0");
    is ($csv->callbacks, $warn = undef,	"no callbacks for 0");
    ok ($csv = Text::CSV->new ({ callbacks => 1	}),	"new");
    like ($warn, qr{: ignored\n},	"warn for 1");
    is ($csv->callbacks, $warn = undef,	"no callbacks for 1");
    ok ($csv = Text::CSV->new ({ callbacks => \1	}),	"new");
    like ($warn, qr{: ignored\n},	"warn for \\1");
    is ($csv->callbacks, $warn = undef,	"no callbacks for \\1");
    ok ($csv = Text::CSV->new ({ callbacks => ""	}),	"new");
    like ($warn, qr{: ignored\n},	"warn for ''");
    is ($csv->callbacks, $warn = undef,	"no callbacks for ''");
    ok ($csv = Text::CSV->new ({ callbacks => []	}),	"new");
    like ($warn, qr{: ignored\n},	"warn for []");
    is ($csv->callbacks, $warn = undef,	"no callbacks for []");
    ok ($csv = Text::CSV->new ({ callbacks => sub {}	}),	"new");
    like ($warn, qr{: ignored\n},	"warn for sub {}");
    is ($csv->callbacks, $warn = undef,	"no callbacks for sub {}");
    }

ok ($csv = Text::CSV->new (),	"new");
is ($csv->callbacks, undef,		"no callbacks");
ok ($csv->bind_columns (\my ($c, $s)),	"bind");
ok ($csv->getline (*DATA),		"parse ok");
is ($c, 1,				"key");
is ($s, "foo",				"value");
$s = "untouched";
ok ($csv->getline (*DATA),		"parse bad");
is ($c, 1,				"key");
is ($s, "untouched",			"untouched");
ok ($csv->getline (*DATA),		"parse bad");
is ($c, "foo",				"key");
is ($s, "untouched",			"untouched");
ok ($csv->getline (*DATA),		"parse good");
is ($c, 2,				"key");
is ($s, "bar",				"value");
eval { is ($csv->getline (*DATA), undef,"parse bad"); };
my @diag = $csv->error_diag;
is ($diag[0], 3006,			"too many values");

# These tests are for the method
foreach my $args ([""], [1], [[]], [sub{}], [1,2], [1,2,3],
		  [undef,"error"], ["error",undef],
		  ["%23bad",sub {}], ["error",sub{0;},undef,1],
		  ["error",[]], ["error","error"], ["",sub{0;}],
		  [sub{0;},0], [[],""]) {
    eval { $csv->callbacks (@$args); };
    my @diag = $csv->error_diag;
    is ($diag[0], 1004,			"invalid callbacks");
    is ($csv->callbacks, undef,		"not set");
    }

# These tests are for invalid arguments *inside* the hash
foreach my $arg (undef, 0, 1, \1, "", [], $csv) {
    eval { $csv->callbacks ({ error => $arg }); };
    my @diag = $csv->error_diag;
    is ($diag[0], 1004,			"invalid callbacks");
    is ($csv->callbacks, undef,		"not set");
    }
ok ($csv->callbacks (bogus => sub { 0; }), "useless callback");

my $error = 3006;
sub ignore {
    is ($_[0], $error, "Caught error $error");
    $csv->SetDiag (0); # Ignore this error
    } # ignore

my $idx = 1;
ok ($csv->auto_diag (1), "set auto_diag");
my $callbacks = {
    error        => \&ignore,
    after_parse  => sub {
	my ($c, $av) = @_;
	# Just add a field
	push @$av, "NEW";
	},
    before_print => sub {
	my ($c, $av) = @_;
	# First field set to line number
	$av->[0] = $idx++;
	# Maximum 2 fields
	@{$av} > 2 and splice @{$av}, 2;
	# Minimum 2 fields
	@{$av} < 2 and push @{$av}, "";
	},
    };
is (ref $csv->callbacks ($callbacks), "HASH", "callbacks set");
ok ($csv->getline (*DATA),		"parse ok");
is ($c, 1,				"key");
is ($s, "foo",				"value");
ok ($csv->getline (*DATA),		"parse bad, skip 3006");
ok ($csv->getline (*DATA),		"parse good");
is ($c, 2,				"key");
is ($s, "bar",				"value");

$csv->bind_columns (undef);
ok (my $row = $csv->getline (*DATA),	"get row");
is_deeply ($row, [ 1, 2, 3, "NEW" ],	"fetch + value from hook");

$error = 2012; # EOF
ok ($csv->getline (*DATA),		"parse past eof");

ok ($csv->eol ("\n"), "eol for output");
open my $fh, ">", $tfn or die "$tfn: $!";
ok ($csv->print ($fh, [ 0, "foo"    ]), "print OK");
ok ($csv->print ($fh, [ 0, "bar", 3 ]), "print too many");
ok ($csv->print ($fh, [ 0           ]), "print too few");
close $fh;

open $fh, "<", $tfn or die "$tfn: $!";
is (do { local $/; <$fh> }, "1,foo\n2,bar\n3,\n", "Modified output");
close $fh;

# Test the non-IO interface
ok ($csv->parse ("10,blah,33\n"),			"parse");
is_deeply ([ $csv->fields ], [ 10, "blah", 33, "NEW" ],	"fields");

ok ($csv->combine (11, "fri", 22, 18),			"combine - no hook");
is ($csv->string, qq{11,fri,22,18\n},			"string");

is ($csv->callbacks (undef), undef,			"clear callbacks");

is_deeply (Text::CSV::csv (in => $tfn, callbacks => $callbacks),
    [[1,"foo","NEW"],[2,"bar","NEW"],[3,"","NEW"]], "using getline_all");

open $fh, ">", $tfn or die "$tfn: $!\n";
print $fh <<"EOC";
1,foo
2,bar
3,baz
4,zoo
EOC
close $fh;

open $fh, "<", $tfn or die "$tfn: $!\n";
$csv->callbacks (after_parse => sub { $_[1][0] eq 3 and return \"skip" });
is_deeply ($csv->getline_all ($fh), [[1,"foo"],[2,"bar"],[4,"zoo"]], "skip");
close $fh;

__END__
1,foo
1
foo
2,bar
3,baz,2
1,foo
3,baz,2
2,bar
1,2,3
