use strict;
use warnings;
use Test::More;

my @array;

sub array_lvalue :lvalue {
    @array
}

(array_lvalue()) = (1, 2);
is_deeply([@array], [1, 2], 'array-valued lvalue sub is assignable');

@array = ();
my $orig = \&array_lvalue;

sub wrapped_array_lvalue :lvalue {
    $orig->(@_)
}

(wrapped_array_lvalue()) = (3, 4);
is_deeply([@array], [3, 4], 'array-valued lvalue sub aliases through coderef wrapper');

my $ctx = '';

sub context_array_lvalue :lvalue {
    $ctx = wantarray ? 'list' : defined(wantarray) ? 'scalar' : 'void';
    @array
}

(context_array_lvalue()) = (5, 6);
is($ctx, 'list', 'list assignment calls array-valued lvalue sub in list context');
is_deeply([@array], [5, 6], 'array-valued lvalue sub remains assignable after wantarray check');

done_testing;
