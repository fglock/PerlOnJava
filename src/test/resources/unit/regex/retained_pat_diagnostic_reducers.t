use strict;
use warnings;
use Test::More tests => 11;

{
    require Encode;
    my $malformed = "a\x80\n";
    utf8::downgrade($malformed);
    Encode::_utf8_on($malformed);
    my $matched = eval { $malformed =~ /(\n\r|\r)$/ };
    ok(!defined($matched), 'malformed UTF-8 match terminates fatally');
    like($@, qr/Malformed UTF-8 character/,
         'malformed UTF-8 fatal diagnostic is preserved');
}

{
    eval q{ qr{()(?1)}n };
    like($@, qr/Reference to nonexistent group/,
         'noncapturing recursion reports nonexistent group');
}

{
    local $_ = 'ab';
    my $pattern = qr/(?{ s!!x! })/;
    my $ok = eval {
        /$pattern/;
        /a/;
        /$pattern/;
        /b/;
        /$pattern/;
        //;
        1;
    };
    ok(!$ok, 'empty pattern recursion dies');
    like($@, qr/Infinite recursion via empty pattern/,
         'empty pattern recursion diagnostic');
}

{
    eval q{ "q0" =~ /\p{__::Is0}/ };
    like($@, qr/Unknown user-defined property name \\p\{__::Is0}/,
         'qualified missing user property diagnostic');
}

for my $row (
    ["qr\x04foo\x04", 'Perl 133921 qr delimiter'],
    ["\x04ignored", 'Perl 133921 source EOT'],
    ["s\x04foo\x04bar\x04", 'Perl 133921 substitution delimiter'],
    ["\x04a a\xfa\0", 'Perl 133921 binary source EOT'],
) {
    local $_ = 'foo';
    utf8::downgrade($row->[0]);
    eval $row->[0];
    is($@, '', $row->[1]);
}

{
    eval "s/[(?{//xx";
    like($@,
         qr{Unmatched \[ in regex; marked by <-- HERE in m/\[ <-- HERE \(\?\{/ at \(eval \d+\) line 1\.},
         'regexp-component overflow diagnostic and eval location');
}
