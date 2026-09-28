use strict;
use warnings;
use Test::More;

{
    my @values = 'A';
    sub {
        my $first = shift;
        my $second = shift;
        @values = ();
        package DB;
        () = caller 0;
        main::is("$DB::args[0]-$DB::args[1]", '-B',
            'DB args follows an array element released after argument passing');
    }->($values[0], 'B');
}

done_testing;
