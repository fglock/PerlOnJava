use strict;
use warnings;
use feature 'state';
use Test::More;

state sub answer;
sub answer { 42 }

is answer, 42, 'ordinary sub definition fulfills a state-sub forward declaration';
is &answer, 42, 'ampersand call uses the fulfilled state-sub declaration';

{
    sub shadowed { 43 }
    state sub shadowed;
    sub shadowed { 44 }

    is shadowed, 44, 'state-sub forward declaration shadows an existing package sub';
    is &shadowed, 44, 'ampersand call uses the shadowing state-sub declaration';
}
done_testing;
