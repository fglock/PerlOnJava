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

# named subroutine with typeglob assignment

*x = sub { print "HERE @_\n" };
&x(123);

# &name calls the subroutine reusing existing @_

@_ = (456, "ABC");
&x;

# named subroutine

sub modify_argument { $_[0]++ }
my $v = 13;
modify_argument($v);
print "not " if $v != 14; say "ok # subroutine list argument is an alias to the argument; returned $v";
$v = 13;
modify_argument $v;
print "not " if $v != 14; say "ok # subroutine scalar argument is an alias to the argument; returned  $v";

# constant subroutine

sub CONST () { "VALUE" }
$v = CONST . "2";
print "not" if $v ne "VALUE2"; say "ok # constant subroutine returned $v";

# subroutine call operator precedence

sub no_proto { "VALUE" }
$v = no_proto . "2";
print "not" if $v ne "VALUE2"; say "ok # subroutine without prototype returned $v";

$v = no_proto or "2";
print "not" if $v ne "VALUE"; say "ok # subroutine without prototype returned $v";

5;    # return value

