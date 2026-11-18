use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More;

my ($temporary, $path) = tempfile();
close $temporary or die "close temporary file: $!";

my $stash;
{
    no strict 'refs';
    no warnings 'once';
    $stash = \%{'DetachedIOGlobOwner::'};
    open 'DetachedIOGlobOwner::FH', '>', $path
        or die "open package filehandle: $!";
}

my $detached = delete $stash->{FH};
ok(!exists $stash->{FH}, 'package IO glob is removed from its stash');
ok(defined fileno($detached), 'detached glob retains the open IO slot');
ok((print {$detached} "detached write\n"), 'detached glob remains writable');
ok(close($detached), 'detached glob closes its retained IO slot');

open my $reader, '<', $path or die "open written file: $!";
my $contents = do { local $/; <$reader> };
is($contents, "detached write\n", 'detached glob write reaches the file');
close $reader or die "close written file: $!";
unlink $path or die "remove written file: $!";

done_testing();
