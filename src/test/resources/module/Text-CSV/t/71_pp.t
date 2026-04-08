#!/usr/bin/perl

# tests for bug report fixes or patches.

use strict;
$^W = 1;

use Test::More tests => 104;


BEGIN { $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0; }

BEGIN {
    require_ok "Text::CSV";
    plan skip_all => "Cannot load Text::CSV" if $@;
}

#warn Text::CSV->backend;

my $csv = Text::CSV->new( { sep_char => "\t", blank_is_undef => 1, allow_whitespace => 1 } );

ok $csv->parse(qq|John\t\t"my notes"|);

is_deeply ([ $csv->fields ], [ "John", undef, "my notes" ], "Tab with allow_white_space");



# 2009-04-23 rt#45215

my $str = "this,is,some,csv,data\n";

$csv = Text::CSV->new;
$csv->parse($str);

is( $csv->string, $str );

#=pod

# 2009-05-16
# getline() handles having escaped null

my $opts = {
  'escape_char' => '"',
  'quote_char' => '"',
  'binary' => 1,
  'sep_char' => ','
};

my $eol  = "\r\n";
my $blob = ( join "", map { chr $_ } 0 .. 255 ) x 1;
#my $blob = ( join "", map { chr $_ } 0 .. 2 ) x 1;

$csv = Text::CSV->new( $opts );

open( FH, '>__test.csv' ) or die $!;
binmode FH;

# writting
ok( $csv->print( *FH, [ $blob ] ) );
close( FH );

# reading
open( FH, "__test.csv" ) or die $!;
binmode FH;

$opts->{eol} = $eol;
$csv = Text::CSV->new( $opts );

ok( my $colref = $csv->getline( *FH ) );

is( $colref->[0], $blob, "blob" );

close( FH );

#exit;
unlink( '__test.csv' );

#=cut

# 2009-07-30
# getline() handles a 0 staring multiline


# writting
open( FH, '>__test.csv' ) or die $!;
binmode FH;


ok( $csv->print( *FH, [ "00" ] ) );
ok( $csv->print( *FH, [ "\00" ] ) );
ok( $csv->print( *FH, [ "0\0" ] ) );
ok( $csv->print( *FH, [ "\0\0" ] ) );

ok( $csv->print( *FH, [ "0\n0" ] ) );
ok( $csv->print( *FH, [ "\0\n0" ] ) );
ok( $csv->print( *FH, [ "0\n\0" ] ) );
ok( $csv->print( *FH, [ "\0\n\0" ] ) );

ok( $csv->print( *FH, [ "\"0\n0" ] ) );
ok( $csv->print( *FH, [ "\"\0\n0" ] ) );
ok( $csv->print( *FH, [ "\"0\n\0" ] ) );
ok( $csv->print( *FH, [ "\"\0\n\0" ] ) );

ok( $csv->print( *FH, [ "\"0\n\"0" ] ) );
ok( $csv->print( *FH, [ "\"\0\n\"0" ] ) );
ok( $csv->print( *FH, [ "\"0\n\"\0" ] ) );
ok( $csv->print( *FH, [ "\"\0\n\"\0" ] ) );

ok( $csv->print( *FH, [ "0\n0", "0\n0" ] ) );
ok( $csv->print( *FH, [ "\0\n0", "\0\n0" ] ) );
ok( $csv->print( *FH, [ "0\n\0", "0\n\0" ] ) );
ok( $csv->print( *FH, [ "\0\n\0", "\0\n\0" ] ) );

$csv->always_quote(1);

ok( $csv->print( *FH, [ "", undef, "0\n", "", "\0\n0" ] ) );


close( FH );

# reading
open( FH, "__test.csv" ) or die $!;
binmode FH;

is( $csv->getline( *FH )->[0], "00",   '*00' ); # Test::More warns 00
is( $csv->getline( *FH )->[0], "\00",  '\00' );
is( $csv->getline( *FH )->[0], "0\0",  '0\0' );
is( $csv->getline( *FH )->[0], "\0\0", '\0\0' );

is( $csv->getline( *FH )->[0], "0\n0",   '*0\n0' ); # Test::More warns 00
is( $csv->getline( *FH )->[0], "\0\n0",  '\0\n0' );
is( $csv->getline( *FH )->[0], "0\n\0",  '0\n\0' );
is( $csv->getline( *FH )->[0], "\0\n\0", '\0\n\0' );

is( $csv->getline( *FH )->[0], "\"0\n0",   '\"0\n0' );
is( $csv->getline( *FH )->[0], "\"\0\n0",  '\"\0\n0' );
is( $csv->getline( *FH )->[0], "\"0\n\0",  '\"0\n\0' );
is( $csv->getline( *FH )->[0], "\"\0\n\0", '\"\0\n\0' );

is( $csv->getline( *FH )->[0], "\"0\n\"0",   '\"0\n\"0' );
is( $csv->getline( *FH )->[0], "\"\0\n\"0",  '\"\0\n\"0' );
is( $csv->getline( *FH )->[0], "\"0\n\"\0",  '\"0\n\"\0' );
is( $csv->getline( *FH )->[0], "\"\0\n\"\0", '\"\0\n\"\0' );

is( $csv->getline( *FH )->[1], "0\n0",   '*0\n0' ); # Test::More warns 00
is( $csv->getline( *FH )->[1], "\0\n0",  '\0\n0' );
is( $csv->getline( *FH )->[1], "0\n\0",  '0\n\0' );
is( $csv->getline( *FH )->[1], "\0\n\0", '\0\n\0' );

$csv->blank_is_undef(1);

my $col = $csv->getline( *FH );

is( $col->[0], "", '' );
is( $col->[1], undef, '' );
is( $col->[2], "0\n", '' );
is( $col->[3], "", '' );
is( $col->[4], "\0\n0", '' );

close( FH );

unlink( '__test.csv' );

# 2010-06-18 reported by https://rt.cpan.org/Public/Bug/Display.html?id=58356

$csv = Text::CSV->new ({ binary => 1, quote_space => 0 });
my @list = (
    "a a",
    "b,b",
    "c ,c",
);

ok( $csv->combine( @list ) );
is( $csv->string, q{a a,"b,b","c ,c"} );


# 2010-06-22 reported
{
    $csv = Text::CSV->new ({ binary => 1, sep_char => ';', always_quote => 1 });

    open( FH, '>__test.csv' ) or die $!;
    binmode FH;

    ok( $csv->print( *FH, [ 0, qq{t"t"\n} ] ) );

    close( FH );

    open( FH, "__test.csv" ) or die $!;
    binmode FH;

    my $col = $csv->getline( *FH );

    is( $col->[0], "0" );
    is( $col->[1], qq{t"t"\n} );
    close( FH );

    unlink( '__test.csv' );
}


# 2010-10-13 reported by hiratara
{
    $csv = Text::CSV->new ({ binary => 1, eol => $/, always_quote => 1 });

    open( FH, '>__test.csv' ) or die $!;
    binmode FH;

    ok( $csv->print( *FH, [qw/A 01/] ) );
    ok( $csv->print( *FH, [qw/B 02/] ) );
    close( FH );

    open( FH, "__test.csv" ) or die $!;
    binmode FH;

    my $col = $csv->getline( *FH );

    is( $col->[0], 'A' );
    is( $col->[1], '01' );

    $col = $csv->getline( *FH );

    is( $col->[0], 'B' );
    is( $col->[1], '02' );
    close( FH );

    unlink( '__test.csv' );
}


# 2010-10-13 reported(2) by hiratara
{
    $csv = Text::CSV->new ({ binary => 1, eol => $/ });

    open( FH, '>__test.csv' ) or die $!;
    binmode FH;

    ok( $csv->print( *FH, [qw/1 0"/] ) );
    ok( $csv->print( *FH, [qw/2 0"/] ) );
    close( FH );

    open( FH, "__test.csv" ) or die $!;
    binmode FH;

    my $col = $csv->getline( *FH );

    is( $col->[0], '1' );
    is( $col->[1], '0"' );

    $col = $csv->getline( *FH );

    is( $col->[0], '2' );
    is( $col->[1], '0"' );

    close( FH );

    unlink( '__test.csv' );
}


{ # previous three test merged
    $csv = Text::CSV->new ({ binary => 1, eol => $/ });

    open( FH, '>__test.csv' ) or die $!;
    binmode FH;

    ok( $csv->print( *FH, [ 0, qq{t"t"\n} ] ) );
    ok( $csv->print( *FH, [qw/A 01/] ) );
    ok( $csv->print( *FH, [qw/1 0"/] ) );
    ok( $csv->print( *FH, [undef,undef] ) );
    ok( $csv->print( *FH, [qw/1 0"/] ) );
    ok( $csv->print( *FH, [qw/A 01/] ) );
    close( FH );

    open( FH, "__test.csv" ) or die $!;
    binmode FH;

    my $col = $csv->getline( *FH );
    is( $col->[0], "0" );
    is( $col->[1], qq{t"t"\n} );

    $col = $csv->getline( *FH );
    is( $col->[0], 'A' );
    is( $col->[1], '01' );

    $col = $csv->getline( *FH );
    is( $col->[0], '1' );
    is( $col->[1], '0"' );

    $col = $csv->getline( *FH );
    is( $col->[0], '' );
    is( $col->[1], '' );

    $col = $csv->getline( *FH );
    is( $col->[0], '1' );
    is( $col->[1], '0"' );

    $col = $csv->getline( *FH );
    is( $col->[0], 'A' );
    is( $col->[1], '01' );
    close( FH );

    unlink( '__test.csv' );
}


SKIP: { # https://rt.cpan.org/Ticket/Display.html?id=83705
skip "pp only for now", 3 unless Text::CSV->is_pp;

my $csv = Text::CSV->new(
    {
        binary              => 1,
        allow_loose_escapes => 1,
        allow_loose_quotes  => 1,
        sep_char            => q{;},
        escape_char         => q{"},
        quote_char          => q{"}
    }
);

$csv->parse(q{"6RE";"EINKAUF";"5";"";"2,5" HD"});
is_deeply([$csv->fields], ["6RE","EINKAUF","5","",'2,5" HD']);

my $csv_dump = q{"6RE";"EINKAUF";"5";"";"2,5" HD"
"LIDL";"-2"};

open( FH, '>__test.csv' ) or die $!;
print FH $csv_dump;
close FH;

open FH, '<__test.csv';

is_deeply( $csv->getline(*FH), ["6RE","EINKAUF","5","",'2,5" HD'] );
is_deeply( $csv->getline(*FH), ['LIDL','-2'] );

close FH;

unlink( '__test.csv' );

}

{ # imported from t/70_rt.t
my $csv = Text::CSV->new ({ escape_char => "\\", auto_diag => 1 });

ok( $csv->parse(q{1,"\,",3}) );
is_deeply ([ $csv->fields ], [ 1, ",", 3 ], "escaped sep in quoted field");
ok( $csv->parse(q{1,"2\,4",3}) );
is_deeply ([ $csv->fields ], [ 1, "2,4", 3 ], "escaped sep in quoted field");

$csv->allow_unquoted_escape(1);
ok( $csv->parse(q{1,\,,3}) );
is_deeply ([ $csv->fields ], [ 1, ",", 3 ], "escaped sep in quoted field");
ok( $csv->parse(q{1,2\,4,3}) );
is_deeply ([ $csv->fields ], [ 1, "2,4", 3 ], "escaped sep in quoted field");
}

{ # https://github.com/makamaka/Text-CSV/pull/3

    {
        package FakeFileHandleForEOF;

        sub new { return bless { line => "foo,bar,baz\n" }, shift }

        sub getline {
            my $self = shift;
            return delete $self->{line};
        }

        sub eof {
            my $self = shift;
            return not exists $self->{line};
        }
    }

    my $csv = Text::CSV->new({binary => 1});
    my $fh  = FakeFileHandleForEOF->new;
    ok(!$fh->eof);
    eval { is_deeply( $csv->getline($fh), [qw[ foo bar baz ]]) };
    is($@, '', "no exception thrown");
    ok($fh->eof);
}

{ # https://github.com/makamaka/Text-CSV/issues/14
  # https://rt.cpan.org/Ticket/Display.html?id=109719
    SKIP: {
        skip "requires Encode", 1 unless eval "require Encode";
        my $csv = Text::CSV->new({empty_is_undef => 1, blank_is_undef => 1});
        my $line = "foo,,bar,";
        Encode::_utf8_on($line);
        $csv->parse($line);
        my @fields = $csv->fields;
        is_deeply \@fields => ['foo', undef, 'bar', undef];
    }
}
