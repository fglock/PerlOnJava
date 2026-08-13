use strict;
use warnings;
use Test::More;

open my $fh, '>', \my $output or die $!;
say $fh 'current-version say';
close $fh;

is($output, "current-version say\n", 'current Perl feature bundle is enabled by default');

{
    use v5.8.0;
    open my $old_version_fh, '>', \my $old_version_output or die $!;
    say $old_version_fh 'say survives an older use VERSION declaration';
    close $old_version_fh;
    is(
        $old_version_output,
        "say survives an older use VERSION declaration\n",
        'an older use VERSION does not erase interpreter defaults',
    );
}

done_testing;
