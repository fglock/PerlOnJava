use strict;
use warnings;
use Config;
use Test::More;

plan skip_all => 'requires a 64-bit Perl UV' if $Config{uvsize} < 8;

my $highest = '9223372036854775807';
my $first_unsigned = '9223372036854775808';
my $before_uvmax = '18446744073709551614';
my $uvmax = '18446744073709551615';

sub capture_chr {
    my ($value) = @_;
    my (@warnings, $character, $error);
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        $character = eval { chr($value) };
        $error = $@;
    }
    return ($character, $error, join '', @warnings);
}

{
    no warnings qw(non_unicode portable utf8);
    my ($character, $error, $warning) = capture_chr($highest);
    ok(defined($character) && !$error,
       'chr accepts the highest signed-IV scalar');
    is(sprintf('%X', ord($character)), '7FFFFFFFFFFFFFFF',
       'chr preserves the highest signed-IV scalar exactly');
    is($warning, '', 'highest signed-IV chr is warning-free');
}

for my $case (
    [ $first_unsigned, '8000000000000000', 'first value above signed IV max' ],
    [ $before_uvmax, 'FFFFFFFFFFFFFFFE', 'UV_MAX minus one' ],
    [ $uvmax, 'FFFFFFFFFFFFFFFF', 'UV_MAX' ],
) {
    my ($value, $hex, $name) = @$case;
    my ($character, $error, $warning) = capture_chr($value);
    ok(!defined($character), "$name does not produce a character");
    like($error, qr/Use of code point 0x$hex is not allowed/,
         "$name reports the rejected code point");
    like($error, qr/permissible max is 0x7FFFFFFFFFFFFFFF/,
         "$name reports the signed-IV ceiling");
    is($warning, '', "$name is fatal rather than a warning");
}

for my $case (
    [ -1, 'Invalid negative number \(-1\) in chr',
      'negative integer' ],
    [ -0.5, 'Invalid negative number \(-0\.5\) in chr',
      'negative fractional value' ],
) {
    my ($value, $warning_pattern, $name) = @$case;
    my ($character, $error, $warning) = capture_chr($value);
    ok(defined($character) && !$error, "$name returns a replacement character");
    is(ord($character), 0xFFFD, "$name becomes U+FFFD");
    like($warning, qr/$warning_pattern/, "$name emits Perl's utf8 warning");
}

{
    my $error = eval q{
        use warnings FATAL => 'utf8';
        my $ignored = chr(-1);
        '';
    };
    like($@, qr/Invalid negative number \(-1\) in chr/,
         'negative chr warning obeys FATAL utf8');
}

{
    my ($literal, $error);
    {
        no warnings qw(non_unicode portable utf8);
        $literal = eval q{ chr(0x8000000000000000) };
        $error = $@;
    }
    ok(!defined($literal) && $error =~ /0x8000000000000000/,
       'constant chr cannot wrap through a narrower host conversion');
}

sub bytes_chr {
    use bytes;
    return chr($_[0]);
}

is(ord(bytes_chr(0x81828384)), 0x84,
   'bytes chr retains low-eight-bit wrapping');
is(ord(bytes_chr($uvmax)), 0xFF,
   'bytes chr keeps its UV_MAX low byte');

sub describe_chr {
    my ($producer) = @_;
    my (@warnings, $character, $error);
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        $character = eval { $producer->() };
        $error = $@;
    }
    return join '|',
        defined($character) ? 'defined' : 'undef',
        $error || '',
        join('', @warnings),
        defined($character) ? sprintf('%X', ord($character)) : '',
        defined($character) ? (utf8::is_utf8($character) ? 'utf8' : 'bytes') : '';
}

for my $case (
    [ 0xFF,    sub { chr(0xFF) },    'Latin-1 boundary' ],
    [ 0x100,   sub { chr(0x100) },   'first non-Latin-1 scalar' ],
    [ 0x10000, sub { chr(0x10000) }, 'supplementary scalar' ],
) {
    my ($value, $constant, $name) = @$case;
    my $variable = sub { chr($value) };
    is(describe_chr($constant), describe_chr($variable),
       "$name has constant-versus-variable chr parity");
    is(sprintf('%X', ord($constant->())), sprintf('%X', $value),
       "$name constant chr preserves ord");
}

{
    no warnings qw(portable void);
    my ($constant_error, $variable_error);
    eval { my $ignored = chr(0x8000000000000000) };
    $constant_error = $@;
    my $value = $first_unsigned;
    eval { my $ignored = chr($value) };
    $variable_error = $@;
    like($constant_error, qr/0x8000000000000000.*0x7FFFFFFFFFFFFFFF/,
         'constant form rejects the first value above signed IV max');
    s/ at \Q$0\E line \d+\.\n\z// for $constant_error, $variable_error;
    is($constant_error, $variable_error,
       'signed-IV rejection boundary has constant-versus-variable parity');
}

done_testing;
