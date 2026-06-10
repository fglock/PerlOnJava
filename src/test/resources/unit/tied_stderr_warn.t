use strict;
use warnings;
use Test::More tests => 1;

package WarnTie::Handle;

sub TIEHANDLE {
    my $class = shift;
    return bless \$_[0], $class;
}

sub PRINT {
    my $self = shift;
    $$self .= join '', @_;
}

package main;

my $err = '';
tie *STDERR, 'WarnTie::Handle', $err;
warn "warning\n";
print STDERR "printed\n";
{
    no warnings 'untie';
    untie *STDERR;
}

is($err, "warning\nprinted\n", 'warn writes through tied STDERR');
