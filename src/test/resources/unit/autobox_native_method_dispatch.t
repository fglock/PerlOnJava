use strict;
use warnings;
use Test::More;
use autobox::universal qw(type);

our @AUTOBOX_DEBUG;
sub capture_autobox_debug { push @AUTOBOX_DEBUG, $_[0] }

is(type(undef), 'UNDEF', 'type() identifies undefined values');
is(type(42), 'INTEGER', 'type() identifies integer values');
is(type(0 == 1), 'INTEGER', 'type() identifies numeric boolean values');
is(type(2 < 1), 'INTEGER', 'type() preserves the numeric flag on false comparison results');
is(type(3.5), 'FLOAT', 'type() identifies floating point values');
is(type(42 / 3), 'FLOAT', 'type() preserves floating point numeric flags');
my $integer_for_division = 42;
is(type($integer_for_division / 3), 'FLOAT',
    'type() preserves floating point flags for variable division results');
is(type('text'), 'STRING', 'type() identifies strings');
is(type([]), 'ARRAY', 'type() identifies array references');
is(type({}), 'HASH', 'type() identifies hash references');
is(type(sub { }), 'CODE', 'type() identifies code references');

{
    use autobox DEBUG => \&capture_autobox_debug;
    my $debug_bindings = shift @AUTOBOX_DEBUG;
    is(ref($debug_bindings), 'HASH', 'DEBUG coderefs receive normalized bindings');
}

{
    use autobox UNIVERSAL => 'Test::Autobox::Universal',
        DEBUG => \&capture_autobox_debug;
    my $debug_bindings = shift @AUTOBOX_DEBUG;
    is_deeply($debug_bindings->{INTEGER}, ['Test::Autobox::Universal'],
        'DEBUG includes UNIVERSAL mappings in concrete type bindings');
    ok(!exists $debug_bindings->{UNDEF},
        'UNIVERSAL does not add a mapping for undefined values');
    my $undefined = eval { undef->universal };
    like($@, qr/Can't (?:call method|locate object method)/,
        'UNIVERSAL mappings do not autobox undefined values');
}

{
    use autobox DEFAULT => 'autobox::universal',
        UNDEF => 'autobox::universal';
    is(42->type, 'INTEGER', 'autobox::universal type() works as an autobox method');
    is(undef->type, 'UNDEF', 'UNDEF can use an explicit method binding');
}

BEGIN {
    package Test::Autobox::Scalar;
    sub value { $_[0] }
    sub new { bless {}, $_[0] }

    package Test::Autobox::Integer;
    sub kind { 'integer:' . $_[0] }

    package Test::Autobox::Array;
    sub size { scalar @{$_[0]} }

    package Test::Autobox::DirectArray;
    sub size { scalar @{$_[0]} }

    package Test::Autobox::DirectHash;
    sub size { scalar keys %{$_[0]} }

    package Test::Autobox::Universal;
    sub universal { 'universal' }

    package Test::Autobox::MergedA;
    sub merged { 'A' }

    package Test::Autobox::MergedB;
    sub second { 'B' }

    package Test::Autobox::Auto;
    our $AUTOLOAD;
    sub AUTOLOAD { $AUTOLOAD }
}

{
    use autobox SCALAR => 'Test::Autobox::Scalar';
    is('native value'->value, 'native value',
        'a lexical scalar binding dispatches a method on an unblessed native value');
    is('native value'->autobox_class, 'Test::Autobox::Scalar',
        'autobox_class reports the class selected for a native value');
    is(ref(Test::Autobox::Scalar->new), 'Test::Autobox::Scalar',
        'a package name used as a bareword invocant is not autoboxed');
}

{
    use autobox;
    BEGIN { is($^H & 0x80020000, 0x80020000, 'autobox exposes its lexical hint bits') }
    my $missing = eval { []->missing_default_method };
    like($@, qr/^Can't call method "missing_default_method" on unblessed reference/,
        'default reference mappings preserve unblessed-reference diagnostics');
    no autobox;
    BEGIN { is($^H & 0x80000000, 0, 'no autobox clears its lexical hint bit') }
}

{
    my @unboxed = qw(a b);
    my $unboxed_result = eval { @unboxed->size };
    like($@, qr/Can't (?:call method|locate object method)/,
        'direct aggregate invocants do not dispatch before autobox is enabled');
    unlike($@, qr/unblessed reference/,
        'inactive autobox does not rewrite an aggregate into a reference invocant');
}

{
    use autobox INTEGER => 'Test::Autobox::Integer',
        ARRAY => 'Test::Autobox::Array',
        UNIVERSAL => 'Test::Autobox::Universal';
    is(7->kind, 'integer:7', 'INTEGER has its own binding');
    is([1, 2, 3]->size, 3, 'ARRAY references receive their configured binding');
    is('x'->universal, 'universal', 'UNIVERSAL applies after concrete scalar types');
    is([1]->universal, 'universal', 'UNIVERSAL applies to reference types');
}

{
    use autobox ARRAY => 'Test::Autobox::DirectArray',
        HASH => 'Test::Autobox::DirectHash';
    my @array = qw(a b c);
    my %hash = (a => 1, b => 2);
    my $array_ref = \@array;
    my $hash_ref = \%hash;
    is(@array->size, 3, 'a direct array variable is passed to its method by reference');
    is(%hash->size, 2, 'a direct hash variable is passed to its method by reference');
    is((@array)->size, 3, 'a parenthesized array invocant is passed by reference');
    is(((%hash))->size, 2, 'a nested parenthesized hash invocant is passed by reference');
    is(@$array_ref->size, 3, 'an array reference dereferenced as an invocant is preserved');
    is(%$hash_ref->size, 2, 'a hash reference dereferenced as an invocant is preserved');
}

{
    use autobox ARRAY => 'Test::Autobox::DirectArray';
    my @array = qw(a b);
    {
        no autobox;
        my $disabled = eval { @array->size };
        like($@, qr/Can't (?:call method|locate object method)/,
            'direct aggregate dispatch is disabled by no autobox');
        unlike($@, qr/unblessed reference/,
            'no autobox restores ordinary aggregate method lookup');
    }
    is(@array->size, 2, 'direct aggregate dispatch returns after no autobox scope');
}

{
    use autobox INTEGER => ['Test::Autobox::MergedA', 'Test::Autobox::MergedB'];
    is(4->merged, 'A', 'merged mappings preserve the first class in lookup order');
    is(4->second, 'B', 'merged mappings search later classes');
}

{
    use autobox SCALAR => 'Test::Autobox::Auto';
    is('native'->missing, 'Test::Autobox::Auto::missing',
        'an autobox mapping preserves AUTOLOAD method names');
}

my $outside = eval { 'native value'->value };
like($@, qr/Can't (?:call method|locate object method)/,
    'the scalar binding does not leak past its lexical block');

{
    use autobox SCALAR => 'Test::Autobox::Scalar';
    {
        no autobox qw(SCALAR);
        my $disabled = eval { 'native value'->value };
        like($@, qr/Can't (?:call method|locate object method)/,
            'no autobox countermanding disables the lexical binding');
    }
    is('native value'->value, 'native value',
        'the outer binding is restored after the nested scope');
}

done_testing();
