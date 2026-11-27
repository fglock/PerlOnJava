use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);
use PerlIO::via;

our @events;
our $fileno_initialized = 0;

package Local::FilenoBeforeWrite;
sub PUSHED {
    push @main::events, 'PUSHED';
    return bless {}, $_[0];
}
sub FILENO {
    push @main::events, 'FILENO';
    $main::fileno_initialized = 1;
    return fileno($_[1]);
}
sub WRITE {
    my ($self, $data, $fh) = @_;
    die 'WRITE before FILENO' unless $main::fileno_initialized;
    push @main::events, 'WRITE';
    print {$fh} $data or return -1;
    return length($data);
}
sub FLUSH {
    push @main::events, 'FLUSH';
    return 0;
}

package main;
my ($raw, $path) = tempfile();
close $raw or die "close temporary file: $!";
@events = ();
$fileno_initialized = 0;

open my $fh, '>:via(Local::FilenoBeforeWrite)', $path or die "open via handle: $!";
print {$fh} "payload\n" or die "write via handle: $!";
close $fh or die "close via handle: $!";

my %event_index;
for my $index (0 .. $#events) {
    $event_index{$events[$index]} //= $index;
}
ok(exists $event_index{FILENO} && exists $event_index{WRITE}
        && $event_index{FILENO} < $event_index{WRITE},
    'FILENO runs during open before WRITE');
open my $input, '<', $path or die "open raw file: $!";
is(scalar(<$input>), "payload\n", 'the initialized layer writes through its lower handle');
close $input;
unlink $path or die "unlink temporary file: $!";

done_testing;
