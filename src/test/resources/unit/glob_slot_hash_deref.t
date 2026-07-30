use strict;
use warnings;
use lib 'src/test/resources/unit/lib';
use Test::More tests => 6;
use Symbol qw(gensym);
use GlobSlotRegistry;

my $glob = gensym();
$ {*$glob}{Keys} = {alpha => 1, beta => 2};
my %copy = %{ $ {*$glob}{Keys} };

is($copy{alpha}, 1, 'glob scalar slot can hold a hash reference');
is_deeply(\%copy, {alpha => 1, beta => 2},
    'nested glob-slot hash dereference preserves contents');

my @names = qw(PF AF type);
@{$ {*$glob}{Keys}}{@names} = @{$ {*$glob}{Keys}}{@names};

ok(exists $ {*$glob}{Keys}{PF},
    'hash slice assignment through a glob scalar slot creates keys');
is(scalar(keys %{$ {*$glob}{Keys}}), 5,
    'glob-slot hash slice assignment preserves and extends contents');

my $registry = GlobSlotRegistry->new;
$registry->register_handlers({debug => sub { 1 }, timeout => sub { 1 }});
is_deeply([$registry->registered_keys], [qw(debug timeout)],
    'glob-slot slice assignment works in a required module');

my $handler_name = 'debug';
is(&{$ {*$registry}{Keys}{$handler_name}}(), 1,
    'code dereference accepts a nested glob-slot hash element');
