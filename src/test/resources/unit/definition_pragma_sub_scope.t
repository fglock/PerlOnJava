use strict;
use warnings;
use utf8;
use Test::More tests => 9;

sub ordinary_pos {
    return pos($_[0]);
}

{
    use bytes;
    sub bytes_pos {
        return pos($_[0]);
    }
}

sub warnings_enabled {
    my $undefined;
    return "enabled=$undefined";
}

{
    no warnings 'uninitialized';
    sub warnings_disabled {
        my $undefined;
        return "disabled=$undefined";
    }
}

my $subject = "éx";
$subject =~ /é/g;
is(pos($subject), 1, 'ordinary scope presents the live pos as characters');

{
    use bytes;
    is(ordinary_pos($subject), 1,
        'ordinary sub retains character pos when called from bytes scope');
}
is(bytes_pos($subject), 2,
    'bytes sub retains byte pos when called from ordinary scope');
is(pos($subject), 1, 'opposite-scope pos reads do not mutate the live position');

{
    no warnings 'uninitialized';
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    is(warnings_enabled(), 'enabled=',
        'warnings-enabled sub returns normally from no-warnings caller');
    is(scalar @warnings, 1,
        'warnings-enabled sub retains its definition warning state');
    like($warnings[0], qr/Use of uninitialized value \$undefined/,
        'definition-scoped uninitialized warning is captured');
}

{
    use warnings 'uninitialized';
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    is(warnings_disabled(), 'disabled=',
        'warnings-disabled sub returns normally from warnings caller');
    is(scalar @warnings, 0,
        'warnings-disabled sub retains its definition warning state');
}
