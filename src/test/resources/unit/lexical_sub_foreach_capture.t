use v5.18;
use feature qw(lexical_subs state);
no warnings qw(experimental::lexical_subs);

use Test::More tests => 4;

my $value = 43;
for $value (765) {
    my sub my_sub { $value }
    state sub state_sub { $value }

    is my_sub(), 765, 'my sub captures the localized foreach cell';
    is state_sub(), 43, 'state sub retains its definition-time cell';
}

my sub my_caller_name { (caller 0)[3] }
state sub state_caller_name { (caller 0)[3] }

is my_caller_name(), 'my_caller_name', 'my sub reports its lexical caller name';
is state_caller_name(), 'state_caller_name', 'state sub reports its lexical caller name';
