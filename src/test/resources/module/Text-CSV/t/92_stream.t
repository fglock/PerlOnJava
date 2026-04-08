#!/usr/bin/perl

use strict;
$^W = 1;

#use Test::More "no_plan";
 use Test::More tests => 21;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    use_ok "Text::CSV", ("csv");
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";
    }

my $tfni = "_92test-i.csv"; END { -f $tfni and unlink $tfni } # CRNL
my $tfnn = "_92test-n.csv"; END { -f $tfnn and unlink $tfnn } # CRNL + NL
my $tfno = "_92test-o.csv"; END { -f $tfno and unlink $tfno } # out

my $data =
    "foo,bar,baz,quux\r\n".
    "1,2,3,25\r\n".
    "2,a b,,14\r\n";
open  my $fhi, ">", $tfni or die "$tfni: $!";
print $fhi $data;
close $fhi;
open  my $fhn, ">", $tfnn or die "$tfnn: $!";
{   my $d = $data;
    $d =~ s/5\r\n/5\n/;
    print $fhn $d;
    }
close $fhn;
ok (my $aoa = csv (in => $tfni), "Read default data");;

{   my ($I, $O, @W);
    ok (my $co = Text::CSV->new ({
	eol       => "\n",
	auto_diag => 1,
	callbacks => {
	  before_print => sub {
	    warn ++$O, "\n";
	    $_[1][3] =~ s/x$/y/ or $_[1][3] *= 4;
	    },
	  },
	}), "Create external CSV object");
    open my $fho, ">", $tfno or die "$tfno: $!\n";
    {	local $SIG{__WARN__} = sub { push @W => @_ };
	csv (
	    in        => $tfni,
	    out       => undef,
	    callbacks => {
	      after_parse  => sub {
		warn ++$I, "\n";
		$co->print ($fho, $_[1]);
		},
	      },
	    );
	}
    close $tfno;
    chomp @W;
    is ("@W", "1 1 2 2 3 3", "Old-fashioned streaming");
    }

# Basic straight-forward streaming, no filters/modifiers
unlink $tfno if -e $tfno;
csv (in => $tfni, out => $tfno, quote_space => 0);
ok (-s $tfno, "FILE -> FILE");
is_deeply (csv (in => $tfno), $aoa, "Data is equal");

unlink $tfno if -e $tfno;
open my $fho, ">", $tfno;
csv (in => $tfni, out => $fho,  quote_space => 0);
close   $fho;
ok (-s $tfno, "FILE -> FH");
is_deeply (csv (in => $tfno), $aoa, "Data is equal");

unlink $tfno if -e $tfno;
open    $fhi, "<", $tfni;
csv (in => $fhi,  out => $tfno, quote_space => 0);
close   $fhi;
ok (-s $tfno, "FH   -> FILE");
is_deeply (csv (in => $tfno), $aoa, "Data is equal");

unlink $tfno if -e $tfno;
open    $fhi, "<", $tfni;
open    $fho, ">", $tfno;
csv (in => $fhi,  out => $fho,  quote_space => 0);
close   $fho;
close   $fhi;
ok (-s $tfno, "FH   -> FH");
is_deeply (csv (in => $tfno), $aoa, "Data is equal");

unlink $tfno if -e $tfno;
my @W;
eval {
    local $SIG{__WARN__} = sub { push @W => @_ };
    csv (in => $tfnn, out => $tfno, quote_space => 0);
    };
like ($W[0], qr{\b2016 - EOL\b}, "Inconsistent use of EOL");
ok (-s $tfno, "FH -> FILE (NL => CRNL)");
is_deeply (csv (in => $tfno), $aoa, "Data is equal");
is (do { local (@ARGV, $/) = ($tfno); <> }, $data, "Consistent CRNL");

unlink $tfno if -e $tfno;
csv (
    in          => $tfni,
    out         => $tfno,
    quote_space => 0,
    after_parse => sub { $_[1][1] .= "X" },
    );
ok (-s $tfno, "With after_parse");
my @new = map { my @x = @$_; $x[1] .= "X"; \@x } @$aoa;
is_deeply (csv (in => $tfno), \@new, "Data is equal");

# Prove streaming behavior
my $io = "";
unlink $tfno if -e $tfno;
csv (
    in        => $tfni,
    out       => $tfno,
    on_in     => sub { $io .= "I" },
    callbacks => { before_print => sub { $io .= "O" }},
    );
ok (-s $tfno, "FILE -> FILE");
is_deeply (csv (in => $tfno), $aoa, "Data is equal");
like ($io, qr{^(?:IO)+\z}, "IOIOIO...");
