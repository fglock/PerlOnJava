#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use PerlIO::via;

our @events;
our $tmp_n = 0;

package Local::WriteVia;
sub PUSHED {
    push @main::events, 'PUSHED';
    return bless {}, $_[0];
}
sub WRITE {
    push @main::events, "WRITE:$_[1]";
    return (print { $_[2] } uc($_[1])) ? length($_[1]) : -1;
}
sub CLOSE {
    push @main::events, 'CLOSE';
    return close($_[1]) ? 1 : -1;
}
sub POPPED {
    push @main::events, 'POPPED';
    return 1;
}

package Local::ReadVia;
sub PUSHED { bless {}, $_[0] }
sub READ {
    my ($self, undef, $len, $fh) = @_;
    my $n = sysread($fh, $_[1], $len);
    return $n unless $n;
    $_[1] = lc $_[1];
    return $n;
}

package Local::FillVia;
sub PUSHED { bless {}, $_[0] }
sub FILL {
    my $line = readline($_[1]);
    return defined($line) ? "fill:$line" : undef;
}

package Local::BinmodeVia;
sub PUSHED { bless {}, $_[0] }
sub WRITE {
    return (print { $_[2] } "B:$_[1]") ? length($_[1]) : -1;
}

package Local::RejectVia;
sub PUSHED { return -1 }

package main;

sub tmpfile {
    return "perlio_via_$$." . (++$tmp_n) . ".tmp";
}

sub slurp_raw {
    my ($file) = @_;
    open my $fh, '<:raw', $file or die "open $file: $!";
    local $/;
    my $data = <$fh>;
    close $fh;
    return $data;
}

my @cleanup;

my $write_file = tmpfile();
push @cleanup, $write_file;
@events = ();
open my $out, '>:via(Local::WriteVia)', $write_file or die "open via write: $!";
print {$out} 'abc';
close $out;
is slurp_raw($write_file), 'ABC', 'WRITE callback transforms output';
is_deeply \@events, [qw(PUSHED WRITE:abc CLOSE POPPED)], 'write lifecycle callbacks run';

my $read_file = tmpfile();
push @cleanup, $read_file;
open my $raw_out, '>:raw', $read_file or die "open raw write: $!";
print {$raw_out} "ABCdef";
close $raw_out;
open my $in, '<:via(Local::ReadVia)', $read_file or die "open via read: $!";
is do { local $/; <$in> }, 'abcdef', 'READ callback mutates aliased buffer';
close $in;

my $fill_file = tmpfile();
push @cleanup, $fill_file;
open $raw_out, '>:raw', $fill_file or die "open raw write: $!";
print {$raw_out} "one\ntwo\n";
close $raw_out;
open my $fill_in, '<:via(Local::FillVia)', $fill_file or die "open via fill: $!";
is scalar(<$fill_in>), "fill:one\n", 'FILL callback feeds readline';
is scalar(<$fill_in>), "fill:two\n", 'FILL callback buffers subsequent records';
close $fill_in;

my $binmode_file = tmpfile();
push @cleanup, $binmode_file;
open my $binmode_fh, '>:raw', $binmode_file or die "open raw binmode: $!";
ok binmode($binmode_fh, ':via(Local::BinmodeVia)'), 'binmode pushes via layer';
print {$binmode_fh} 'ok';
close $binmode_fh;
is slurp_raw($binmode_file), 'B:ok', 'binmode-installed via layer writes through callback';

my $reject_file = tmpfile();
push @cleanup, $reject_file;
open my $reject_fh, '>:raw', $reject_file or die "open raw reject: $!";
{
    local $SIG{__WARN__} = sub { };
    ok !binmode($reject_fh, ':via(Local::RejectVia)'), 'failed PUSHED makes binmode false';
}
print {$reject_fh} 'raw';
close $reject_fh;
is slurp_raw($reject_file), 'raw', 'failed binmode leaves original handle installed';

unlink @cleanup;
done_testing;
