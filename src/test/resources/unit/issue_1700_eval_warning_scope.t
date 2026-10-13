use strict;
use warnings;
use Test::More;

BEGIN {
    $| = 1;
    $^W = 1;
}

package Issue1700WarningTarget;
sub wrapped { 'original' }

package Issue1700WarningCaller;
sub install_wrapper {
    no strict 'refs';
    my $name = 'Issue1700WarningTarget::wrapped';
    my $original = *{$name}{CODE};

    no warnings 'redefine';
    eval <<'END_WRAPPER';
package Aspect::Hook;
*Issue1700WarningTarget::wrapped = sub {
    goto &$original if 0;
    'wrapped';
};
1;
END_WRAPPER
    die $@ if $@;
}
sub install_warning_wrapper {
    no strict 'refs';
    my $name = 'Issue1700WarningTarget::wrapped';
    my $original = *{$name}{CODE};

    no warnings 'redefine';
    eval <<'END_WARNING_WRAPPER';
use warnings 'redefine';
package Aspect::Hook;
*Issue1700WarningTarget::wrapped = sub {
    goto &$original if 0;
    'warning-enabled';
};
1;
END_WARNING_WRAPPER
    die $@ if $@;
}

package main;
my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    Issue1700WarningCaller::install_wrapper();
}

is(Issue1700WarningTarget::wrapped(), 'wrapped',
    'eval-installed glob wrapper replaces the original sub');
is_deeply(\@warnings, [],
    'eval inherits no warnings redefine from its caller despite $^W');
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    Issue1700WarningCaller::install_warning_wrapper();
}
like($warnings[0] // '', qr/Subroutine Issue1700WarningTarget::wrapped redefined/,
    'warnings enabled inside eval override the inherited suppression');
is(Issue1700WarningTarget::wrapped(), 'warning-enabled',
    'eval pragma takes effect for the installed wrapper');

done_testing;
