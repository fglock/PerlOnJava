use strict;
use warnings;
use Test::More tests => 4;

{
    package RegexStateScopeCounter;

    sub add {
        my ($self, $n) = @_;
        $self->{x} += $n;
        $self->{y} += $n;
        return $self->{x} + $self->{y};
    }

    sub match_inside {
        my ($self) = @_;
        'inner' =~ /(inner)/;
        return $1;
    }
}

my $counter = bless { x => 1, y => 2 }, 'RegexStateScopeCounter';
'outer' =~ /(outer)/;
is($counter->add(1), 5, 'simple arithmetic method returns its result');
is($1, 'outer', 'method without regex preserves caller match captures');
is($counter->match_inside(), 'inner', 'regex method returns its own capture');
is($1, 'outer', 'regex method restores caller match captures');
