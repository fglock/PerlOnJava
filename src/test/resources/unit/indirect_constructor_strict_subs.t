use strict;
use warnings;
use Test::More;

{
    package Strict::Constructor::Client;
    use strict;
    sub build {
        my $base = new Strict::Constructor::Base;
        return $base;
    }
}

# Define the constructor at runtime without a `package` declaration, leaving
# the class unknown when the lazily compiled `build` body is parsed.
{
    package Strict::Constructor::Registry;
    no strict 'refs';
    *{'Strict::Constructor::Base::new'} = sub { bless {}, shift };
}

my $base = Strict::Constructor::Client::build();
is(ref($base), 'Strict::Constructor::Base',
    'qualified indirect constructor syntax compiles under strict subs');

done_testing;
