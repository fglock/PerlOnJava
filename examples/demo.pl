# A sample Perl script with a bit of everything
# use feature 'say';

my $a = 15;
my $x = $a;
say $x;
$a = 12;
say $a;
say( sub { say @_ } );                     # anon sub
( sub { say 'HERE' } )->(88888);           # anon sub
( sub { say "<@_>" } )->( 88, 89, 90 );    # anon sub
eval ' $a = $a + 1 ';                      # eval string
say $a;
do {
    $a;
    if    (1) { say 123 }
    elsif (3) { say 345 }
    else      { say 456 }
};
print "Finished; value is $a\n";
my ( $i, %j ) = ( 1, 2, 3, 4, 5 );
say(%j);
$a = { a => 'hash-value' };
say $a->{a};
my $b = [ 4, 5 ];
say $b->[1];

5;    # return value

