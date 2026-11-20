use strict;
use warnings;
use Test::More tests => 3;
use Hash::Util qw(fieldhash);
use Function::Parameters {
    fun_cx => { defaults => 'function', install_sub => 'jamitin' },
};

my %watcher;
BEGIN { fieldhash %watcher; }
my $calls;
BEGIN { $calls = 0; }

sub jamitin {
    my ($name, $body) = @_;
    $watcher{$body} = $name;
    $calls++;
}

BEGIN {
    is($calls, 0, 'installer has not run before the declaration');
}

fun_cx via_fun_cx(@) { 1 }

BEGIN {
    is($calls, 1, 'compile-time declaration invokes the installer');
    is_deeply(\%watcher, {}, 'installer callback CV is released after BEGIN');
}
