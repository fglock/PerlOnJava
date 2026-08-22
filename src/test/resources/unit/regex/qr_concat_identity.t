use strict;
use warnings;
use Test::More;

our @calls;
{
    package Local::QrConcatIdentity;
    use overload
        q{""} => sub { ${$_[0]} },
        q{.} => sub {
            push @main::calls, [ref($_[1]) || '', $_[2] ? 1 : 0];
            bless $_[2]
                ? \("$_[1]" . ${$_[0]})
                : \(${$_[0]} . "$_[1]"),
                ref($_[0]);
        };
}

my $text = 'foo';
my $object = bless \$text, 'Local::QrConcatIdentity';
my $bar = qr/bar/;
my $baz = qr/baz/;

@calls = ();
my $plain = $object . $bar;
is_deeply(\@calls, [['Regexp', 0]],
    'ordinary concat passes the original qr object to left overload');
isa_ok($plain, 'Local::QrConcatIdentity',
    'ordinary concat preserves the overloaded result');
is($$plain, "foo$bar", 'ordinary concat retains both string values');

@calls = ();
ok('foobar' =~ /$object$bar/,
    'regex interpolation keeps the overloaded result usable as a pattern');
is_deeply(\@calls, [['', 1], ['Regexp', 0]],
    'regex interpolation preserves qr identity after the initial reverse concat');

@calls = ();
ok('xfoobar y' =~ /x$object$bar y?/,
    'mixed regex interpolation preserves prefix and suffix');
is_deeply(\@calls, [['', 1], ['Regexp', 0], ['', 0]],
    'mixed interpolation continues the overload chain in source order');

@calls = ();
ok('foobarbaz' =~ /$object$bar$baz/,
    'multiple compiled regex operands remain a usable pattern');
is_deeply(\@calls, [['', 1], ['Regexp', 0], ['Regexp', 0]],
    'each compiled regex reaches concat overload with REGEXP identity');

@calls = ();
my $string = "$object$bar";
isa_ok($string, 'Local::QrConcatIdentity',
    'ordinary interpolation preserves the overloaded concat result');
is_deeply(\@calls, [['Regexp', 0]],
    'ordinary interpolation also passes the original qr object');
is($$string, "foo$bar", 'ordinary interpolation retains both string values');

done_testing;
