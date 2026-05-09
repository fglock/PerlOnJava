#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 4;
use File::Temp qw(tempdir);
use File::Spec;

my $tmp = tempdir(CLEANUP => 1);
my $dir = File::Spec->catdir($tmp, 'Hook');
mkdir $dir or die "mkdir $dir: $!";

my $pm = File::Spec->catfile($dir, 'Module.pm');
open my $out, '>', $pm or die "open $pm: $!";
print {$out} <<'EOPM';
use v5.14;

sub mock () { 42 }
sub current_package { __PACKAGE__ }

1;
EOPM
close $out;

my $prefix = 'package Local::Loaded;';
my $hook_called = 0;
my $hook = sub {
    my (undef, $file) = @_;
    return unless $file eq 'Hook/Module.pm';

    $hook_called++;
    open my $in, '<', $pm or die "open $pm: $!";
    return (\$prefix, $in);
};

{
    local @INC = ($hook, @INC);
    my $loaded = eval { require Hook::Module; 1 };
    ok($loaded, '@INC hook scalar prefix plus filehandle loads')
        or diag "\$@ = $@";
}

is($hook_called, 1, '@INC hook handled the requested module');
is(Local::Loaded::current_package(), 'Local::Loaded',
    'filehandle source compiled under scalar prefix package');

my $value = eval q{use strict; Local::Loaded::mock;};
is($value, 42, 'constant sub from prefixed package is visible under strict')
    or diag "\$@ = $@";
