use strict;
use warnings;
use Test::More tests => 6;

{
    package Local::UnaryABI;
    use overload
        '""' => sub {
            $main::string_args = [ @_ ];
            return 'rendered';
        },
        'neg' => sub {
            $main::neg_args = [ @_ ];
            return 17;
        };
}

our ($string_args, $neg_args);
my $value = bless {}, 'Local::UnaryABI';

is("$value", 'rendered', 'string conversion overload runs');
is(scalar(@$string_args), 3, 'string conversion receives three arguments');
ok(!defined $string_args->[1], 'string conversion second argument is undef');
is($string_args->[2], '', 'string conversion third argument is empty string');

is(-$value, 17, 'unary overload runs');
is_deeply([ @$neg_args[1, 2] ], [undef, ''], 'unary overload receives undef and empty string');
