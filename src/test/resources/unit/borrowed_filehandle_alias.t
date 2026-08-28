use strict;
use warnings;
use Test::More;
use Fcntl qw(O_RDWR O_CREAT O_TRUNC SEEK_END);
use File::Temp qw(tempfile);

my ($x, $first_path) = tempfile();
my ($y, $second_path) = tempfile();
close $x;
close $y;

sub open_alias {
    my ($path) = @_;
    sysopen(my $sysfh, $path, O_RDWR | O_CREAT | O_TRUNC, 0666) or die "sysopen: $!";
    open(my $alias, '+>&=', fileno($sysfh)) or die "open alias: $!";
    return ($sysfh, $alias);
}

my ($first_source,  $first)  = open_alias($first_path);
my ($second_source, $second) = open_alias($second_path);

ok((print {$first} 'a'), 'first borrowed alias remains writable after another alias is opened');
ok((print {$second} 'b'), 'second borrowed alias is writable');
ok(seek($first,  0, SEEK_END), 'can seek first borrowed alias');
ok(seek($second, 0, SEEK_END), 'can seek second borrowed alias');
is(tell($first),  1, 'first borrowed alias has its own live file position');
is(tell($second), 1, 'second borrowed alias has its own live file position');

close $first_source;
ok((print {$first} 'c'), 'borrowed alias outlives its closed source handle');
ok(seek($first, 0, SEEK_END), 'borrowed alias remains seekable after source close');
is(tell($first), 2, 'borrowed alias still tracks its file position after source close');

close $first;
close $second;
close $second_source;
unlink $first_path;
unlink $second_path;

done_testing;
