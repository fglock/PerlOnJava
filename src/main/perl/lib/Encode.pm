package Encode;
use strict;
use warnings;
our $VERSION = '3.21';

require Exporter;
our @ISA = qw(Exporter);
our @EXPORT = qw(decode encode encode_utf8 decode_utf8 find_encoding);
our @EXPORT_OK = qw(
    _utf8_off _utf8_on define_encoding from_to is_utf8
    perlio_ok resolve_alias
    encodings
    FB_DEFAULT FB_CROAK FB_QUIET FB_WARN FB_HTMLCREF FB_XMLCREF
    LEAVE_SRC
);

use XSLoader;
XSLoader::load('Encode', $VERSION);

# Save reference to the Java-backed find_encoding before overriding.
# The Java method handles known charsets (UTF-8, latin1, ascii, etc.) directly.
my $_java_find_encoding = \&find_encoding;

# Override find_encoding to add Encode::Alias support.
# The Java backend only recognises hardcoded charset names.  This wrapper
# consults Encode::Alias (loaded by modules like Encode::Locale) to resolve
# custom aliases (coderef, regex, string) before delegating to Java.
{
    no warnings 'redefine';
    my %_resolving;   # per-name recursion guard
    *find_encoding = sub {
        my ($name, $skip_external) = @_;
        return undef unless defined $name;

        # Fast path: try the Java charset lookup first
        my $enc = eval { $_java_find_encoding->($name) };
        return $enc if defined $enc;

        # Guard against circular alias chains for the same name
        return undef if $_resolving{$name};
        local $_resolving{$name} = 1;

        # Consult Encode::Alias if it has been loaded
        if (defined &Encode::Alias::find_alias) {
            my $resolved = eval { Encode::Alias::find_alias("Encode", $name) };
            return $resolved if defined $resolved;
        }

        return undef;
    };
}

sub resolve_alias {
    my ($name) = @_;
    my $enc = find_encoding($name);
    return unless defined $enc;
    return ref($enc) ? $enc->{Name} : $enc;
}

1;
