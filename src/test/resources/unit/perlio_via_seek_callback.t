use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

package Local::ViaSeek;

sub PUSHED { bless {}, $_[0] }

sub FILL {
    my ($self, $fh) = @_;
    return scalar <$fh>;
}

sub SEEK {
    my ($self, $position, $whence, $fh) = @_;
    return seek($fh, $position, $whence) ? 0 : -1;
}

package main;

my ($out, $path) = tempfile(UNLINK => 1);
print {$out} "first\nsecond\n" or die "write seek fixture: $!";
close $out or die "close seek fixture: $!";

open my $in, '<:via(Local::ViaSeek)', $path or die "open via handle: $!";
is scalar(<$in>), "first\n", 'FILL returns the first record';
ok seek($in, 0, 0), 'SEEK callback receives position, whence, and handle in Perl order';
is scalar(<$in>), "first\n", 'successful via seek rewinds the underlying stream';
close $in;

done_testing;
