#!/usr/bin/env perl
use strict;
use warnings;
use File::Spec;
use FindBin;
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $makefile = File::Spec->catfile($root, 'Makefile');
open my $fh, '<', $makefile or die "Cannot read $makefile: $!";
my $text = do { local $/; <$fh> };
close $fh;

like($text, qr/^GRADLE_LAUNCH_ARGS = --no-daemon \$\(GRADLE_ARGS\)$/m,
    'Makefile always disables the persistent Gradle daemon');

my @recipes = grep { /^\s*(?:gradlew\.bat|\.\/gradlew)\s/ }
    split /\n/, $text;
ok(@recipes, 'Makefile contains Gradle wrapper recipes');
for my $recipe (@recipes) {
    like($recipe, qr/\$\(GRADLE_LAUNCH_ARGS\)/,
        "Gradle wrapper recipe uses the single-use launch arguments: $recipe");
}

done_testing;
