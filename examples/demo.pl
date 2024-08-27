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

############################
#  Subroutines

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
print "not " if $v ne "VALUE2"; say "ok # constant subroutine returned $v";

$v = CONST => "2";
print "not " if $v ne "CONST"; say "ok # constant subroutine returned $v";

package Other {
    sub CONST () { "OTHER" }
    print "not " if CONST ne "OTHER"; say "ok # constant subroutine defined in other package";
}
print "not " if CONST ne "VALUE"; say "ok # constant subroutine was not overwritten";

# subroutine call operator precedence

sub no_proto { "VALUE" }
$v = no_proto . "2";
print "not" if $v ne "VALUE2"; say "ok # subroutine without prototype returned $v";

$v = no_proto or "2";
print "not" if $v ne "VALUE"; say "ok # subroutine without prototype returned $v";


############################
#  Objects

# bless() and ref()

$v = {};
print "not" if ref($v) ne "HASH"; say "ok # unblessed reference returns data type";

bless $v, "Pkg";
print "not" if ref($v) ne "Pkg"; say "ok # blessed reference returns package name";

# method is a CODE

my $obj = "123";
my $method = sub { "called" };
print "not" if $obj->$method ne "called"; say "ok # CODE method is called";

# method is in a class

$obj = bless {}, "Obj";

package Obj { sub meth { 123 } }

print "not" if $obj->meth ne "123"; say "ok # method is resolved and called";

5;    # return value

