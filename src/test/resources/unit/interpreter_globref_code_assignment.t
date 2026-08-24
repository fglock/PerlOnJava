use strict;
use warnings;
use Test::More tests => 5;

sub globref {
    no strict 'refs';
    return \*{$_[0]};
}

my @seen;
*{globref('InterpreterGlobrefCodeAssignment::installed')} = sub { push @seen, @_ };
ok(
    InterpreterGlobrefCodeAssignment->can('installed'),
    'CODE assignment through a glob reference installs the slot'
);
InterpreterGlobrefCodeAssignment::installed('outer');
is_deeply(\@seen, ['outer'], 'installed CODE slot is callable');

{
    no warnings 'redefine';
    local *{globref('InterpreterGlobrefCodeAssignment::installed')}
        = sub { push @seen, 'local', @_ };
    InterpreterGlobrefCodeAssignment::installed('inner');
    is_deeply(
        \@seen,
        ['outer', 'local', 'inner'],
        'localized glob-reference assignment is visible'
    );
}

InterpreterGlobrefCodeAssignment::installed('restored');
is_deeply(
    \@seen,
    ['outer', 'local', 'inner', 'restored'],
    'localized glob restores the outer CODE slot'
);
ok(
    globref('InterpreterGlobrefCodeAssignment::installed'),
    'glob-reference helper remains usable'
);
