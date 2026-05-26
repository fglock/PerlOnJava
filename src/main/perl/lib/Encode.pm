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
# consults Encode::Alias (loaded by modules like Encode::Locale) dynamically.
{
    no warnings 'redefine';
    my %_resolving;   # per-name recursion guard
    my %_encoding_cache;

    my $_cached_java_find_encoding = sub {
        my ($name) = @_;
        return undef unless defined $name;

        my $key = lc $name;
        return $_encoding_cache{$key} if exists $_encoding_cache{$key};

        my $enc = eval { $_java_find_encoding->($name) };
        if (defined $enc) {
            $_encoding_cache{$key} = $enc;
            eval {
                $_encoding_cache{lc $enc->name} ||= $enc;
                $_encoding_cache{lc $enc->mime_name} ||= $enc;
            };
        }
        return $enc;
    };

    *find_encoding = sub {
        my ($name, $skip_external) = @_;
        return undef unless defined $name;

        # Guard against circular alias chains for the same name
        return undef if $_resolving{$name};
        local $_resolving{$name} = 1;

        # Consult Encode::Alias first so dynamic aliases can override names
        # that the Java backend also knows, such as "locale".
        if (defined &Encode::Alias::find_alias) {
            my $resolved = eval { Encode::Alias::find_alias("Encode", $name) };
            return $resolved if defined $resolved;
        }

        return $_cached_java_find_encoding->($name);
    };

}

sub resolve_alias {
    my ($name) = @_;
    my $enc = find_encoding($name);
    return unless defined $enc;
    return ref($enc) ? $enc->{Name} : $enc;
}

# Delegate to Encode::Alias for alias management.
# Modules like XML::SAX::PurePerl call Encode::define_alias() directly.
sub define_alias {
    require Encode::Alias;
    goto &Encode::Alias::define_alias;
}

1;
